package org.lsposed.corepatch.hook

import android.util.Log
import java.util.Collections

/**
 * 安装事务级的调用者门控。
 *
 * 目的：所有签名绕过只在"可信调用者发起的安装"内生效；其它调用者（例如任意 app 通过
 * PackageInstaller 提交探针包）看到的系统行为与原生完全一致，检测器无法用
 * "提交未签名 / 签名不匹配的 APK，再读 session 的 EXTRA_STATUS" 判断 pm 被修改。
 *
 * 判定依据（都由 Binder 强制，调用者无法伪造）：
 *  1. 安装发起者 UID ∈ {0 root, 1000 system, 2000 shell}
 *  2. 发起者包名属于系统安装器白名单
 *     - PackageInstallerService.createSessionInternal 里 mAppOps.checkPackage(callingUid, ...)
 *       保证包名必须属于调用者，普通 app 无法把自己的 session 标成系统安装器。
 *
 * 两个入口共用同一套判定：
 *  - PackageInstallerSession 的校验方法：commit 阶段解析 APK 时触发，是最早的通路，
 *    也是"提交探针包读 EXTRA_STATUS"唯一会经过的地方；
 *  - InstallPackageHelper 的安装事务入口：install 阶段。
 * 传入的对象可以是 session 也可以是 InstallRequest，[installerUidOf] 会自己适配。
 */
object CallerGate {
    private const val TAG = "CorePatch"

    private const val ROOT_UID = 0
    private const val SYSTEM_UID = 1000
    private const val SHELL_UID = 2000

    /** 常见系统安装器。国产 ROM 若用了别的包名，看日志补进来即可。 */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /**
     * 找不到任何可挂载的入口时置位：所有绕过退回"全局生效"的旧行为，
     * 宁可少隐藏也不要把安装功能锁死。
     */
    @Volatile
    var degraded = false
        private set

    private val state = ThreadLocal<Boolean>()
    private val depth = ThreadLocal<Int>()

    /** 同一 (uid, 包名) 只记一次，避免探针循环刷屏 */
    private val reported: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun markDegraded(reason: String) {
        degraded = true
        Log.e(TAG, "caller gate degraded: $reason, bypasses fall back to global")
    }

    fun isTrusted(): Boolean = degraded || state.get() == true

    /**
     * 进入一次安装 / 校验调用。session 校验链路存在嵌套，所以用深度计数：
     * 只有最外层退出时才清上下文，嵌套层取"或"，避免内层 exit 把外层上下文清掉。
     */
    fun enter(owner: Any?) {
        if (degraded) return
        val trusted = isTrustedOwner(owner)
        val currentDepth = (depth.get() ?: 0) + 1
        depth.set(currentDepth)
        state.set(if (currentDepth == 1) trusted else state.get() == true || trusted)
    }

    fun exit() {
        val currentDepth = (depth.get() ?: 1) - 1
        if (currentDepth <= 0) {
            depth.remove()
            state.remove()
        } else {
            depth.set(currentDepth)
        }
    }

    private fun isTrustedOwner(owner: Any?): Boolean {
        if (owner == null) return false
        val uid = installerUidOf(owner)
        if (uid == ROOT_UID || uid == SYSTEM_UID || uid == SHELL_UID) return true
        val pkg = installerPackageNameOf(owner)
        if (pkg != null && TRUSTED_INSTALLERS.contains(pkg)) return true
        if (reported.add("$uid/$pkg")) {
            Log.i(TAG, "caller gate: bypass skipped for installer uid=$uid pkg=$pkg")
        }
        return false
    }

    /**
     * 同时适配两种对象：
     *  - InstallRequest#getInstallerPackageUid()
     *  - PackageInstallerSession#getInstallerUid()（session 创建者的真实 UID）
     * 再退到字段链 mInstallArgs.mInstallSource.mInstallerPackageUid 与 mInstallerUid。
     */
    private fun installerUidOf(owner: Any): Int {
        for (methodName in arrayOf("getInstallerPackageUid", "getInstallerUid")) {
            runCatching {
                val uid = owner.javaClass.getMethod(methodName).invoke(owner) as Int
                if (uid >= 0) return uid
            }
        }
        runCatching {
            var target: Any? = owner
            for (fieldName in arrayOf("mInstallArgs", "mInstallSource", "mInstallerPackageUid")) {
                target = readField(target, fieldName) ?: return@runCatching
            }
            val uid = target
            if (uid is Int && uid >= 0) return uid
        }
        runCatching {
            val uid = readField(owner, "mInstallerUid")
            if (uid is Int && uid >= 0) return uid
        }
        return -1
    }

    private fun installerPackageNameOf(owner: Any): String? {
        runCatching {
            // PackageInstallerSession 上的实现可能是 package-private，用 declaredMethod
            val method = owner.javaClass.getDeclaredMethod("getInstallerPackageName")
            method.isAccessible = true
            return method.invoke(owner) as? String
        }
        return null
    }

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }
}
