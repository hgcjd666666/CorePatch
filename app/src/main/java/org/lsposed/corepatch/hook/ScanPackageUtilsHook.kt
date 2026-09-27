package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.hostClassLoader

object ScanPackageUtilsHook : BaseHook() {
    override val name = "ScanPackageUtilsHook"

    private const val TAG = "CorePatch"

    @SuppressLint("PrivateApi")
    override fun hook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val scanPackageUtilsClazz =
            hostClassLoader.loadClass("com.android.server.pm.ScanPackageUtils")
        val assertMinSignatureSchemeIsValidMethod =
            scanPackageUtilsClazz.declaredMethods.first { m -> m.name == "assertMinSignatureSchemeIsValid" }
        hookBefore(assertMinSignatureSchemeIsValidMethod) { callback ->
            val pkg = callback.args.firstOrNull()
            val trusted = CallerGate.isTrustedFor(pkg)
            val allowUnsigned = Config.isAllowUnsignedApkEnabled()
            // 未签名包与"只有 v1 签名"的包，SigningDetails 的 signatureSchemeVersion 都是 1，
            // 在这个点上区分不了，所以把"允许安装未签名 APK"也作为钥匙之一。
            // 默认关闭时本方法仍受调用者门控保护：能走到这里的包必须先通过解析层，
            // 而未签名探针在解析层就被拦住了。
            val bypass = Config.isBypassVerificationEnabled() && (trusted || allowUnsigned)
            Log.i(
                TAG,
                "assertMinSignatureSchemeIsValid: trusted=$trusted, allowUnsigned=$allowUnsigned, " +
                    "bypass=$bypass, pkg=" + pkg?.javaClass?.simpleName
            )
            if (bypass) {
                callback.returnAndSkip(null)
            }
        }
    }
}
