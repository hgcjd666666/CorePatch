package org.lsposed.corepatch.hook

import android.util.Log

/**
 * 安装事务级的调用者门控。
 *
 * 目的：所有签名绕过只在“可信调用者发起的安装事务”内生效；事务之外（例如任意 app
 * 通过 PackageInstaller 提交探针包）系统行为与原生完全一致，检测器无法用
 * “提交未签名 / 签名不匹配的 APK，再读 session 的 EXTRA_STATUS” 判断 pm 被修改。
 *
 * 判定依据（都由 Binder 强制，调用者无法伪造）：
 *  1. 安装发起者 UID ∈ {0 root, 1000 system, 2000 shell}
 *  2. 发起者包名属于系统安装器白名单
 *     - PackageInstallerService.createSessionInternal 里 mAppOps.checkPackage(callingUid, ...)
 *       保证包名必须属于调用者，普通 app 无法把自己的 session 标成系统安装器。
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
     * 找不到安装入口方法时置位：所有绕过退回“全局生效”的旧行为，
     * 宁可少隐藏也不要把安装功能锁死。
     */
    @Volatile
    var degraded = false
        private set

    private val state = ThreadLocal<Boolean>()

    /** 同一 (uid, 包名) 只记一次，避免探针循环刷屏 */
    private val reported: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun markDegraded(reason: String) {
        degraded = true
        Log.e(TAG, "caller gate degraded: $reason, bypasses fall back to global")
    }

    fun isTrusted(): Boolean = degraded || state.get() == true

    fun enter(requests: List<*>) {
        if (degraded) return
        state.set(requests.any { isTrustedRequest(it) })
    }

    fun exit() {
        state.remove()
    }

    private fun isTrustedRequest(request: Any?): Boolean {
        if (request == null) return false
        val uid = installerUid(request)
        if (uid == ROOT_UID || uid == SYSTEM_UID || uid == SHELL_UID) return true
        val pkg = installerPackageName(request)
        if (pkg != null && TRUSTED_INSTALLERS.contains(pkg)) return true
        if (reported.add("$uid/$pkg")) {
            Log.i(TAG, "caller gate: bypass skipped for installer uid=$uid pkg=$pkg")
        }
        return false
    }

    private fun installerUid(request: Any): Int {
        // InstallRequest#getInstallerPackageUid()
        runCatching {
            val uid = request.javaClass.getMethod("getInstallerPackageUid").invoke(request) as Int
            if (uid >= 0) return uid
        }
        // 兜底：InstallRequest.mInstallArgs.mInstallSource.mInstallerPackageUid
        runCatching {
            var target: Any? = request
            for (name in arrayOf("mInstallArgs", "mInstallSource", "mInstallerPackageUid")) {
                target = readField(target, name) ?: return@runCatching
            }
            if (target is Int && target >= 0) {
                val uid = target
                return uid
            }
        }
        return -1
    }

    private fun installerPackageName(request: Any): String? {
        runCatching {
            return request.javaClass.getMethod("getInstallerPackageName").invoke(request) as? String
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
