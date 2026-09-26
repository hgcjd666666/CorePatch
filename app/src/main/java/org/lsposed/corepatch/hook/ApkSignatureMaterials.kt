package org.lsposed.corepatch.hook

import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * 判断 APK 里是否存在签名材料，用来把两种能力拆开：
 *
 *  - **有签名材料、但校验不通过**（内容被改过、摘要对不上）：绕过它代价低，
 *    因为 APK 本身带着证书，pm 记录的签名仍然来自 APK，外部看不出"设备允许乱装包"。
 *  - **完全没有签名材料**（未签名 APK）：这是**最容易被检测**的能力 ——
 *    任何应用提交一个未签名 APK、再读安装会话的 EXTRA_STATUS，就能确认 pm 被改过。
 *    所以单独做一个开关，默认关闭。
 */
object ApkSignatureMaterials {

    fun hasSignatureMaterial(apkPath: String): Boolean =
        hasV1Signature(apkPath) || hasApkSigningBlock(apkPath)

    /** v1：META-INF 下的 .RSA / .DSA / .EC / .SF */
    private fun hasV1Signature(apkPath: String): Boolean {
        return try {
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence().any { entry ->
                    val name = entry.name.uppercase()
                    name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                            name.endsWith(".EC") || name.endsWith(".SF"))
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * v2/v3：APK Signing Block 紧邻 Central Directory 之前，布局是
     * [size(8)][pairs][size(8)]["APK Sig Block 42"(16)]，
     * 所以 magic 正好落在 cdOffset-16 .. cdOffset。
     */
    private fun hasApkSigningBlock(apkPath: String): Boolean {
        return try {
            RandomAccessFile(apkPath, "r").use { raf ->
                val length = raf.length()
                if (length < 32) return false

                val tailSize = minOf(length, 0x10000L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tail)

                // 从后往前找 EOCD 签名 PK\x05\x06
                var eocd = -1
                for (i in tail.size - 22 downTo 0) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) {
                        eocd = i
                        break
                    }
                }
                if (eocd < 0) return false

                // EOCD 偏移 16 起 4 字节小端 = Central Directory offset
                val cdOffset = (tail[eocd + 16].toLong() and 0xFF) or
                    ((tail[eocd + 17].toLong() and 0xFF) shl 8) or
                    ((tail[eocd + 18].toLong() and 0xFF) shl 16) or
                    ((tail[eocd + 19].toLong() and 0xFF) shl 24)
                if (cdOffset < 16 || cdOffset > length) return false

                val magic = ByteArray(16)
                raf.seek(cdOffset - 16)
                raf.readFully(magic)
                String(magic, Charsets.US_ASCII) == "APK Sig Block 42"
            }
        } catch (t: Throwable) {
            false
        }
    }
}
