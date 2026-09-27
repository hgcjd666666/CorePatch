package org.lsposed.corepatch.hook

import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    private const val ROOT_UID = 0
    private const val SYSTEM_UID = 1000
    private const val SHELL_UID = 2000

    /** 常见系统安装器。国产 ROM 若用了别的包名，看日志补进来即可。 */
    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /**
     * 系统安装器的 UID，由 [INSTALLER_PACKAGES] 解析一次后缓存。
     *
     * 判定必须走 UID 而不是 installerPackageName：后者是"谁发起安装"，任何持有
     * INSTALL_PACKAGES 的调用者都能自由设置（createSessionInternal 对它会跳过
     * mAppOps.checkPackage 校验）。实测例子：用 MT 管理器装包时，
     * installerPackageName=bin.mt.plus 而 installerUid=10218（系统安装器），
     * 因为 MT 管理器把安装委托给了系统安装器。
     * 安装器自身更新（覆盖安装）不会改变 UID，所以解析一次即可。
     */
    private val installerUids: Set<Int> by lazy { resolveInstallerUids() }

    private fun resolveInstallerUids(): Set<Int> {
        val result = mutableSetOf<Int>()
        runCatching {
            val ipm = Class.forName("android.app.AppGlobals")
                .getMethod("getPackageManager").invoke(null) ?: return result
            val getPackageUid = ipm.javaClass.getMethod(
                "getPackageUid", String::class.java,
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            INSTALLER_PACKAGES.forEach { name ->
                runCatching { getPackageUid.invoke(ipm, name, 0L, 0) as Int }
                    .getOrNull()
                    ?.takeIf { it >= 0 }
                    ?.let { result.add(it) }
            }
        }
        alreadyReported("resolved installer uids=$result", -1, null)
        return result
    }

    /**
     * 找不到任何可挂载的入口时置位：所有绕过退回"全局生效"的旧行为，
     * 宁可少隐藏也不要把安装功能锁死。
     */
    @Volatile
    var degraded = false
        private set

    private val state = ThreadLocal<Boolean>()
    private val depth = ThreadLocal<Int>()

    /**
     * sessionId -> (是否可信, 过期时间)。
     *
     * 解析阶段（ApkSignatureVerifier 之类）只能拿到 APK 路径，拿不到调用者身份。
     * 但 session 安装时 APK 位于 /data/app/vmdl<sessionId>.tmp/ 下，路径里就带着
     * sessionId，而 session 上存着创建者的真实 mInstallerUid。于是在 session 阶段
     * （能可靠拿到身份）把结论记进这张表，解析时按路径反查即可 —— 不依赖 ThreadLocal，
     * 也就不会因为跨线程或热重载而失效。
     */
    private val sessionTrust = ConcurrentHashMap<Int, Pair<Boolean, Long>>()
    private val sessionDirPattern = Regex("/vmdl(\\d+)\\.tmp/")
    private const val SESSION_TRUST_TTL_MS = 15 * 60 * 1000L

    /** 同一 (uid, 包名) 只记一次，避免探针循环刷屏 */
    private val reported: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun markDegraded(reason: String) {
        degraded = true
        XposedHelper.log("caller gate degraded: $reason, bypasses fall back to global")
    }

    fun isTrusted(): Boolean = degraded || state.get() == true

    /**
     * 进入一次安装 / 校验调用。session 校验链路存在嵌套，所以用深度计数：
     * 只有最外层退出时才清上下文，嵌套层取"或"，避免内层 exit 把外层上下文清掉。
     */
    fun enter(owner: Any?) {
        if (degraded) return
        // 优先复用 session 阶段记下的结论（按 sessionId 反查，install 阶段的
        // InstallRequest 也有 getSessionId()），拿不到才现场判定身份
        val trusted = trustFromSessionTable(owner) ?: isTrustedOwner(owner)
        val currentDepth = (depth.get() ?: 0) + 1
        depth.set(currentDepth)
        state.set(if (currentDepth == 1) trusted else state.get() == true || trusted)
    }

    /**
     * 在 session 阶段记录该 session 的安装者是否可信，供解析阶段按 APK 路径反查。
     * 必须在任何 APK 解析之前调用（挂在 session 的校验/安装入口即可）。
     */
    fun rememberSession(session: Any?) {
        if (session == null) return
        val id = sessionIdOf(session)
        if (id == null) {
            // 读不到就写不进表，install 阶段会查不到而退回按 uid 判定，必须可见
            alreadyReported("cannot read sessionId from " + session.javaClass.name, -1, null)
            return
        }
        val trusted = isTrustedOwner(session)
        sessionTrust[id] = trusted to (System.currentTimeMillis() + SESSION_TRUST_TTL_MS)
        pruneSessions()
        if (trusted) {
            XposedHelper.log("caller gate: session $id marked trusted for path lookup")
        }
    }

    /**
     * 统一的信任判定，供各解析点使用：
     *  1. 当前线程有安装事务上下文（session/install 入口设置的 ThreadLocal）；
     *  2. 或者能从候选对象得到 APK 路径，且该 sessionId 在表中被标记为可信。
     *
     * candidate 可以是路径字符串，也可以是带路径的包对象（AndroidPackage / ParsedPackage
     * 等的 getBaseApkPath / getPath / getCodePath）。第二个来源不依赖线程上下文，
     * 所以在 install 阶段（那里拿不到 session 的 ThreadLocal）也能成立。
     */
    fun isTrustedFor(candidate: Any?): Boolean {
        if (isTrusted()) return true
        val path = when (candidate) {
            null -> null
            is String -> candidate
            else -> apkPathOf(candidate)
        }
        return isTrustedApkPath(path)
    }

    private fun apkPathOf(pkg: Any): String? {
        for (methodName in arrayOf("getBaseApkPath", "getPath", "getCodePath")) {
            runCatching {
                val path = pkg.javaClass.getMethod(methodName).invoke(pkg) as? String
                if (path != null) return path
            }
        }
        return null
    }

    /** 按 APK 路径反查该次安装是否可信（路径形如 /data/app/vmdl<sessionId>.tmp/...） */
    fun isTrustedApkPath(apkPath: String?): Boolean {
        val id = sessionIdFromPath(apkPath) ?: return false
        val entry = sessionTrust[id] ?: return false
        if (entry.second < System.currentTimeMillis()) {
            sessionTrust.remove(id)
            return false
        }
        return entry.first
    }

    /**
     * sessionId 的取法：PackageInstallerSession 上是字段（各版本可见性/名字略有差异），
     * InstallRequest 上是 getSessionId()。两种都试，字段用 declaredField 以免可见性变化。
     */
    private fun sessionIdOf(owner: Any): Int? {
        for (fieldName in arrayOf("sessionId", "mSessionId")) {
            runCatching {
                val field = owner.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.getInt(owner)
            }
        }
        for (methodName in arrayOf("getSessionId", "getSessionID")) {
            runCatching {
                val value = owner.javaClass.getMethod(methodName).invoke(owner) as? Int
                if (value != null) return value
            }
        }
        return null
    }

    /** 按 sessionId 复用 session 阶段记下的结论；表里没有则返回 null */
    private fun trustFromSessionTable(owner: Any?): Boolean? {
        if (owner == null) return null
        val id = sessionIdOf(owner) ?: return null
        val entry = sessionTrust[id] ?: return null
        if (entry.second < System.currentTimeMillis()) {
            sessionTrust.remove(id)
            return null
        }
        return entry.first
    }

    private fun sessionIdFromPath(path: String?): Int? =
        path?.let { sessionDirPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun pruneSessions() {
        val now = System.currentTimeMillis()
        sessionTrust.entries.removeAll { it.value.second < now }
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
        val pkg = installerPackageNameOf(owner)

        // root 与 shell：pm install / adb install / su -c pm install
        if (uid == ROOT_UID || uid == SHELL_UID) {
            alreadyReported("trusted", uid, pkg)
            return true
        }
        // 系统安装器：按 UID 判定（见 installerUids 的说明）。
        // 可以用 distrust_system_installer 关掉这份信任：任何应用都能通过 Intent
        // 拉起系统安装器诱导用户点击安装，关掉之后只能用命令行安装。
        if (!Config.isDistrustSystemInstallerEnabled()) {
            if (uid in installerUids) {
                alreadyReported("trusted(system installer)", uid, pkg)
                return true
            }
            // 兜底：某些 ROM 的安装器以应用 UID 运行并把自己标成名单内包名。
            // 只在调用者无力伪写时成立（普通应用伪写会被 mAppOps.checkPackage 拒绝）。
            if (pkg != null && INSTALLER_PACKAGES.contains(pkg) && uid !in installerUids) {
                alreadyReported("trusted(installer name)", uid, pkg)
                return true
            }
        }
        // 严格模式额外排除 system(1000)：部分 ROM 的系统组件以该身份提交安装
        if (!Config.isStrictCallerGateEnabled() && uid == SYSTEM_UID) {
            alreadyReported("trusted", uid, pkg)
            return true
        }
        alreadyReported("skipped", uid, pkg)
        return false
    }

    /** 同一 (结论, uid, 包名) 只记一次，避免探针循环或开机扫描刷屏 */
    private fun alreadyReported(verdict: String, uid: Int, pkg: String?): Boolean {
        if (!reported.add("$verdict:$uid/$pkg")) return true
        XposedHelper.log("caller gate: $verdict, installer uid=$uid pkg=$pkg")
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
