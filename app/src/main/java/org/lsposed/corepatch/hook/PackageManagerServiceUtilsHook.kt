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
        hookBefore(verifySignaturesMethod) { callback ->
            val pkgName = packageNameOf(callback.args.firstOrNull())
            if (Config.isBypassVerificationEnabled()) {
                XposedHelper.log("verifySignatures: BYPASSED, pkg=$pkgName")
                callback.returnAndSkip(false)
            } else if (reportedNotBypassed.add(pkgName ?: "<unknown>")) {
                XposedHelper.log("verifySignatures: NOT bypassed (bypass_verification=false), pkg=$pkgName")
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
