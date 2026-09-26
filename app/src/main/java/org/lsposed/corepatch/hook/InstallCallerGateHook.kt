package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.util.Log
import org.lsposed.corepatch.XposedHelper.findClassIfExists
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hookBefore
import java.lang.reflect.Method

/**
 * 给 [CallerGate] 挂上两个入口。
 *
 * 1. session 校验阶段（更早，也是探针唯一的通路）
 *    PackageInstallerSession 在 commit 时异步解析 APK 并校验签名，这条路径发生在
 *    installPackagesTraced 之前，只挂后者会漏掉它。session 对象上的 mInstallerUid
 *    就是创建时的 Binder 调用者 UID，直接可用。
 * 2. install 阶段
 *    prepare -> scan(parse + verifySignatures) -> reconcile -> commit 全过程同线程同步。
 *
 * 只要有一个入口挂上就不进入 degraded。
 */
object InstallCallerGateHook : BaseHook() {
    override val name = "InstallCallerGateHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val sessionHooked = hookSessionValidation()
        val installHooked = hookInstallEntry()
        if (!sessionHooked && !installHooked) {
            CallerGate.markDegraded("no install entry point matched")
        }
    }

    private fun hookSessionValidation(): Boolean {
        val clazz = findClassIfExists("com.android.server.pm.PackageInstallerSession")
            ?: return false
        var count = 0
        // 嵌套调用由 CallerGate 的深度计数处理，这里可以同时挂多层
        clazz.declaredMethods
            .filter { it.parameterCount == 0 && SESSION_VALIDATION_METHODS.contains(it.name) }
            .forEach { method ->
                hookBefore(method) { callback -> CallerGate.enter(callback.thisObject) }
                hookAfter(method) { CallerGate.exit() }
                count++
            }
        if (count == 0) {
            Log.e("CorePatch", "caller gate: no PackageInstallerSession validation method matched")
        }
        return count > 0
    }

    private fun hookInstallEntry(): Boolean {
        val method = findInstallEntry() ?: return false
        hookBefore(method) { callback ->
            val requests = callback.args.firstOrNull() as? List<*>
            if (requests == null) {
                Log.e("CorePatch", "caller gate: unexpected install entry signature")
            } else {
                // 一次事务里的请求来自同一个 session，取第一个即可
                CallerGate.enter(requests.firstOrNull())
            }
        }
        hookAfter(method) { CallerGate.exit() }
        return true
    }

    private fun findInstallEntry(): Method? {
        // Android 13+ : InstallPackageHelper#installPackagesTraced(List<InstallRequest>)
        findClassIfExists("com.android.server.pm.InstallPackageHelper")
            ?.let { findListMethod(it, "installPackagesTraced") }
            ?.let { return it }
        // Android 10-12 : PackageManagerService#installPackagesTracedLI(List<InstallRequest>)
        findClassIfExists("com.android.server.pm.PackageManagerService")
            ?.let { findListMethod(it, "installPackagesTracedLI") }
            ?.let { return it }
        return null
    }

    private fun findListMethod(clazz: Class<*>, name: String): Method? =
        clazz.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        }

    private val SESSION_VALIDATION_METHODS = setOf(
        "streamValidateAndCommit",
        "validateApkInstallLocked",
        "getAddedApkLitesLocked",
    )
}
