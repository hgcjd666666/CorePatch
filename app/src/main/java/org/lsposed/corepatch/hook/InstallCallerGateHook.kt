package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.util.Log
import org.lsposed.corepatch.XposedHelper.findClassIfExists
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hookBefore

/**
 * 给 [CallerGate] 挂上入口。挂载点分两类，务必都覆盖：
 *
 * 1. PackageInstallerSession 侧（更可靠，session 的方法名各 ROM 基本一致）
 *    - streamValidateAndCommit / validateApkInstallLocked / getAddedApkLitesLocked
 *      commit 阶段解析 APK 并校验签名，是"提交探针包读 EXTRA_STATUS"唯一的通路；
 *    - handleInstall
 *      install 阶段的起点，覆盖 prepare -> verifySignatures -> reconcile。
 *    这些方法跑在 session 自己的 handler 线程上，this 就是 session，
 *    mInstallerUid 是创建时的 Binder 调用者 UID。
 *
 * 2. 安装事务侧（不同版本类名/方法名差异大，全部挂上）
 *    installPackagesTraced / installPackagesTracedLI / installPackagesLI
 *    prepareInstallPackages / preparePackage / preparePackageLI / installPackageLI
 *
 * 只挂后者会漏掉 commit 阶段（实测在部分 ROM 上 InstallPackageHelper 的方法名匹配不到），
 * 只挂前者则依赖 session 与 install 是否同线程。两个都挂才稳。
 */
object InstallCallerGateHook : BaseHook() {
    override val name = "InstallCallerGateHook"

    private const val TAG = "CorePatch"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val sessionHooks = hookSession()
        val installHooks = hookInstallEntry()
        Log.i(TAG, "caller gate: session hooks=$sessionHooks, install hooks=$installHooks")
        if (sessionHooks == 0 && installHooks == 0) {
            CallerGate.markDegraded("no install entry point matched")
        }
    }

    private fun hookSession(): Int {
        val clazz = findClassIfExists("com.android.server.pm.PackageInstallerSession") ?: return 0
        var count = 0
        clazz.declaredMethods
            .filter { it.parameterCount == 0 && SESSION_METHODS.contains(it.name) }
            .forEach { method ->
                hookBefore(method) { callback -> CallerGate.enter(callback.thisObject) }
                hookAfter(method) { CallerGate.exit() }
                count++
            }
        return count
    }

    private fun hookInstallEntry(): Int {
        var count = 0
        for (className in INSTALL_OWNER_CLASSES) {
            val clazz = findClassIfExists(className) ?: continue
            clazz.declaredMethods
                .filter { INSTALL_ENTRY_METHODS.contains(it.name) }
                .filter { method -> method.parameterTypes.any { isInstallRequestType(it) } }
                .forEach { method ->
                    hookBefore(method) { callback -> CallerGate.enter(firstRequest(callback.args)) }
                    hookAfter(method) { CallerGate.exit() }
                    count++
                }
        }
        return count
    }

    private fun isInstallRequestType(type: Class<*>): Boolean =
        List::class.java.isAssignableFrom(type) ||
            type.name.endsWith("InstallRequest") ||
            type.name.endsWith("InstallArgs")

    /** 一次事务里的请求来自同一个 session，取第一个即可 */
    private fun firstRequest(args: Array<Any?>): Any? {
        for (arg in args) {
            when {
                arg is List<*> -> return arg.firstOrNull()
                arg != null && isInstallRequestType(arg.javaClass) -> return arg
            }
        }
        return null
    }

    private val SESSION_METHODS = setOf(
        "streamValidateAndCommit",
        "validateApkInstallLocked",
        "getAddedApkLitesLocked",
        "handleInstall",
    )

    private val INSTALL_OWNER_CLASSES = arrayOf(
        "com.android.server.pm.InstallPackageHelper",
        "com.android.server.pm.PackageManagerService",
    )

    private val INSTALL_ENTRY_METHODS = setOf(
        "installPackagesTraced",
        "installPackagesTracedLI",
        "installPackagesLI",
        "prepareInstallPackages",
        "preparePackage",
        "preparePackageLI",
        "installPackageLI",
    )
}
