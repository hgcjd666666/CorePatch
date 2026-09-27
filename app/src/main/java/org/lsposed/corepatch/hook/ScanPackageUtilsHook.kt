package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.hostClassLoader

object ScanPackageUtilsHook : BaseHook() {
    override val name = "ScanPackageUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val scanPackageUtilsClazz =
            hostClassLoader.loadClass("com.android.server.pm.ScanPackageUtils")
        val assertMinSignatureSchemeIsValidMethod =
            scanPackageUtilsClazz.declaredMethods.first { m -> m.name == "assertMinSignatureSchemeIsValid" }
        hookBefore(assertMinSignatureSchemeIsValidMethod) { callback ->
            // 这个方法在 install 阶段执行，那时没有 session 的线程上下文，
            // 所以要能从 AndroidPackage 取到 APK 路径、按 sessionId 反查
            val trusted = CallerGate.isTrustedFor(callback.args.firstOrNull())
            if (Config.isBypassVerificationEnabled() && trusted) {
                callback.returnAndSkip(null)
            }
        }
    }
}
