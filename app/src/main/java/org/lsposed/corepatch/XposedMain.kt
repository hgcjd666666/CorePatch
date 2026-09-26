package org.lsposed.corepatch

import android.os.Build
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.lsposed.corepatch.Config.printAllConfig
import org.lsposed.corepatch.hook.ApkSignatureVerifierHook
import org.lsposed.corepatch.hook.ApkSigningBlockUtilsHook
import org.lsposed.corepatch.hook.ApplicationInfoHook
import org.lsposed.corepatch.hook.AssetManagerHook
import org.lsposed.corepatch.hook.BaseHook
import org.lsposed.corepatch.hook.InstallCallerGateHook
import org.lsposed.corepatch.hook.InstallPackageHelperHook
import org.lsposed.corepatch.hook.KeySetManagerServiceHook
import org.lsposed.corepatch.hook.MessageDigestHook
import org.lsposed.corepatch.hook.NtConfigListServiceImplHook
import org.lsposed.corepatch.hook.PackageManagerServiceHook
import org.lsposed.corepatch.hook.PackageManagerServiceUtilsHook
import org.lsposed.corepatch.hook.ReconcilePackageUtilsHook
import org.lsposed.corepatch.hook.ScanPackageUtilsHook
import org.lsposed.corepatch.hook.SharedUserSettingHook
import org.lsposed.corepatch.hook.SigningDetailsHook
import org.lsposed.corepatch.hook.StrictJarVerifierHook
import org.lsposed.corepatch.hook.VerificationParamsHook
import org.lsposed.corepatch.hook.VerifyingSessionHook

class XposedMain : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        XposedHelper.setXposedModule(this)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        XposedHelper.log("onSystemServerStarting: Current sdk version is ${Build.VERSION.SDK_INT}")

        XposedHelper.setHostClassLoader(param.classLoader)

        installHooks()
    }

    /**
     * API 102 热重载：在旧代码里决定是否放行。CorePatch 全部是 Java 层 hook，没有模块
     * 自建的线程、native hook 或 JNI 全局引用，旧代可以安全退役。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        XposedHelper.log("onHotReloading: allow hot reload")
        return true
    }

    /**
     * 在新代码里续接。onModuleLoaded / onSystemServerStarting 都不会重放，所以这里要
     * 自己重新绑定框架、重新解析 host classloader，然后重装 hook。
     *
     * 宿主 classloader 必须从旧 hook 句柄反推：hook handle 的 executable 就是被 hook 的
     * 真实方法，其 declaringClass 必然由加载目标类的那个 loader 加载。必须在 unhook
     * 之前读取，unhook 之后 getExecutable() 可能失效。
     * 实测 CCL、getDefaultClassLoader()、模块 loader 的 parent 链在 system_server 上都
     * 拿不到能加载系统类的 loader。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        XposedHelper.setXposedModule(this)

        val oldHandles = param.oldHookHandles
        var hostClassLoader: ClassLoader? = null
        for (handle in oldHandles) {
            val executable = runCatching { handle.executable }.getOrNull()
            val loader = executable?.declaringClass?.classLoader
            if (loader != null) {
                hostClassLoader = loader
                break
            }
        }

        // 覆盖了默认实现就必须自己补上：卸载旧代次安装的全部 hook
        oldHandles.forEach { runCatching { it.unhook() } }

        XposedHelper.log(
            "onHotReloaded: ${param.processName}, ${oldHandles.size} old hooks, loader=$hostClassLoader"
        )
        if (!param.isSystemServer) return
        if (hostClassLoader == null) {
            XposedHelper.log("onHotReloaded: no host classloader derivable from old hooks, skip reinstall")
            return
        }

        XposedHelper.setHostClassLoader(hostClassLoader)
        installHooks()
    }

    private fun installHooks() {
        printAllConfig()
        HOOKS.forEach { it.init() }
    }

    companion object {
        private val HOOKS: List<BaseHook> = listOf(
            ApkSignatureVerifierHook,
            ApkSigningBlockUtilsHook,
            ApplicationInfoHook,
            AssetManagerHook,
            InstallCallerGateHook,
            InstallPackageHelperHook,
            KeySetManagerServiceHook,
            MessageDigestHook,
            NtConfigListServiceImplHook,
            PackageManagerServiceHook,
            PackageManagerServiceUtilsHook,
            ReconcilePackageUtilsHook,
            ScanPackageUtilsHook,
            SharedUserSettingHook,
            SigningDetailsHook,
            StrictJarVerifierHook,
            VerificationParamsHook,
            VerifyingSessionHook,
        )
    }
}
