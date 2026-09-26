package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import org.lsposed.corepatch.XposedHelper.findClassIfExists
import org.lsposed.corepatch.XposedHelper.hookAfter
import org.lsposed.corepatch.XposedHelper.hookBefore
import java.lang.reflect.Method

/**
 * 在安装事务入口（prepare → scan(parse + verifySignatures) → reconcile → commit
 * 全程同线程同步）设置 [CallerGate] 上下文，事务结束清除。
 */
object InstallCallerGateHook : BaseHook() {
    override val name = "InstallCallerGateHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val method = findInstallEntry()
        if (method == null) {
            CallerGate.markDegraded("install entry method not found")
            return
        }

        hookBefore(method) { callback ->
            val requests = callback.args.firstOrNull() as? List<*>
            if (requests == null) {
                // 入口签名不符：退回旧行为，避免安装被卡死
                CallerGate.markDegraded("unexpected install entry signature")
            } else {
                CallerGate.enter(requests)
            }
        }
        // CustomHooker 无论正常返回还是抛异常都会执行 after，这里清理是可靠的
        hookAfter(method) { CallerGate.exit() }
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
}
