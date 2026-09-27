package org.lsposed.corepatch.hook

import android.annotation.SuppressLint
import android.os.Build
import org.lsposed.corepatch.Config
import org.lsposed.corepatch.XposedHelper
import org.lsposed.corepatch.XposedHelper.hookBefore
import org.lsposed.corepatch.XposedHelper.hostClassLoader
import org.lsposed.corepatch.XposedHelper.log
import java.util.Collections

object PackageManagerServiceUtilsHook : BaseHook() {

    /** 只对第一次见到的包名打"未绕过"，避免开机扫描时刷屏 */
    private val reportedNotBypassed: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    override val name = "PackageManagerServiceUtilsHook"

    @SuppressLint("PrivateApi")
    override fun hook() {
        val packageManagerServiceUtilsClazz =
            hostClassLoader.loadClass("com.android.server.pm.PackageManagerServiceUtils")

        // https://cs.android.com/android/platform/superproject/+/android-9.0.0_r61:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=552
        // public static boolean verifySignatures(
        //     PackageSetting pkgSetting,
        //     PackageSetting disabledPkgSetting,
        //     PackageParser.SigningDetails parsedSignatures,
        //     boolean compareCompat,
        //     boolean compareRecover)
        // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r34:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=625
        // 签名不匹配时它是 throw PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE)，
        // 所以 returnAndSkip 才是"放行"（返回值的 false 只是 compatMatch）。
        val verifySignaturesMethod =
            packageManagerServiceUtilsClazz.declaredMethods.first { m -> m.name == "verifySignatures" && m.returnType == Boolean::class.java }
        val deoptimized = XposedHelper.deoptimize(verifySignaturesMethod)
        XposedHelper.log("verifySignatures hook installed: deoptimize=$deoptimized, " +
                "bypass_verification=${Config.isBypassVerificationEnabled()}, " +
                "method=${verifySignaturesMethod.toGenericString()}"
        )
        // 这里刻意不加调用者门控，理由与残留风险：
        //
        // 不加的理由：比对层只在"已安装同名包"时才会被调用，而解析层
        // （ApkSignatureVerifier 等）已经有门控，未签名/读不出证书的探针包在解析阶段
        // 就被挡回去了，根本走不到这里。
        //
        // 已知残留（未实测）：若有人构造一个"与已安装应用同包名、签名有效但与已装包
        // 不同"的 APK，并以普通应用身份提交，本方法会放行 —— 原生返回
        // STATUS_FAILURE_INCOMPATIBLE(7)，本机返回 STATUS_PENDING_USER_ACTION(-1)，
        // 据此可以判断包管理服务被改过。同一条链路上 SigningDetailsHook 的
        // checkCapability / checkCapabilityRecover、KeySetManagerServiceHook、
        // SharedUserSettingHook、checkDowngrade 也都撤了门控，情况相同。
        //
        // 如需堵住：在这几个 hook 上加 CallerGate.isTrustedFor(...) 即可。代价是
        // "禁用 APK 签名验证"的语义会从"允许覆盖安装同包名不同签名"变成
        // "只允许可信发起者这么做"。
        hookBefore(verifySignaturesMethod) { callback ->
            val pkgName = packageNameOf(callback.args.firstOrNull())
            // 诊断：这个方法拿不到 APK 路径/sessionId，能否门控完全取决于 install 阶段的
            // ThreadLocal 是否可靠，先把实际值打出来（只观察，不改变行为）
            if (reportedNotBypassed.add("trusted-probe:$pkgName")) {
                XposedHelper.log(
                    "verifySignatures: callerTrusted=${CallerGate.isTrusted()}, pkg=$pkgName"
                )
            }
            if (Config.isBypassVerificationEnabled()) {
                callback.returnAndSkip(false)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // https://cs.android.com/android/platform/superproject/+/android-13.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=1375
            // public static void checkDowngrade(com.android.server.pm.parsing.pkg.AndroidPackage before, PackageInfoLite after)
            // https://cs.android.com/android/platform/superproject/+/android-14.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=1499
            // OneUI inlines the checkDowngrade methods into one, so we need to hook all methods with the same name and parameter types
            packageManagerServiceUtilsClazz.declaredMethods
                .filter { it.name == "checkDowngrade" && it.returnType == Void.TYPE }
                .filter {
                    it.parameterTypes.lastOrNull()?.name ==
                        "android.content.pm.PackageInfoLite"
                }
                .forEach { checkDowngradeMethod ->
                    hookBefore(checkDowngradeMethod) { callback ->
                        if (Config.isBypassDowngradeEnabled()) {
                            callback.returnAndSkip(null)
                        }
                    }
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ensure verifySignatures success
            // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java;l=621
            val canJoinSharedUserIdMethod =
                packageManagerServiceUtilsClazz.declaredMethods.first { m -> m.name == "canJoinSharedUserId" }
            if (!XposedHelper.deoptimize(canJoinSharedUserIdMethod)) log("failed to deoptimize canJoinSharedUserId")
        }
    }

    /** verifySignatures 的第一个参数是 PackageSetting，取它的包名用于日志 */
    private fun packageNameOf(pkgSetting: Any?): String? {
        if (pkgSetting == null) return null
        return runCatching {
            pkgSetting.javaClass.getMethod("getPackageName").invoke(pkgSetting) as? String
        }.getOrNull()
    }
}
