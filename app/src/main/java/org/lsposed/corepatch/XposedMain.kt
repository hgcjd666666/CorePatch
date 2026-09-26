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
     * 每个 hook 都带稳定 id，框架会原子替换旧代的同 id hook，不会重复挂载。
     */
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 不要调用 super：默认实现会把旧 hook 全部 unhook，那样 setId 的原子替换
        // 就变成"先删后建"，出现空窗期，中途失败还会让模块彻底失效。
        XposedHelper.setXposedModule(this)
        if (!param.isSystemServer) return

        val hostClassLoader = XposedHelper.resolveHostClassLoader()
        XposedHelper.log("onHotReloaded: host class loader = $hostClassLoader")
        XposedHelper.setHostClassLoader(hostClassLoader)
        XposedHelper.log("onHotReloaded: reinstalling hooks in ${param.processName}")
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
