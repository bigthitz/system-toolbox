package com.system.toolbox.core

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 设备激活（公私钥签名校验）。
 *
 * - 设备序列号：读取 /data/misc/bbksn（uid.system 有权限）
 * - 激活码：服务器私钥对 SN 做 SHA256withRSA 签名，
 *   格式 = Base64URL( 2字节大端SN长度 + SN + 256字节签名 )
 * - 校验：激活码内嵌 SN 必须与本机 SN 一致，且签名能被内置公钥验证通过
 */
object Activation {

    const val PREFS = "activation"
    const val KEY_CODE = "activation_code"

    /** RSA-2048 公钥（X.509/SPKI DER 的 Base64，与 server/private_key.pem 配对） */
    private const val PUB_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwe14Oa+jC4den4FHcrsP" +
        "X5WywEYNDKpn/o/QZ1izUfDLwxarmrVI+lTmcOkCFYWhPGh+C/alXk2sKH3iiwsA" +
        "XWkfDCuk7kocCNUm91g35koC0Ylywtf1imaVKozW6OK0c4qkyY+SWSxtLYMD+PG1" +
        "ONDofMFC1835TRxhlIRNx9boxKXV4VuE2Io3y63vxxARNzEQ5SimlrRg3zgA8WmZ" +
        "u2Ms0HL5m8/7Iitmh/JikOG2Svusjz5iYbN6mCnllTuvYThUTWqex/obKSIrfBuC" +
        "7rgrQyAWCcoxONzPDGxOaeamUrwasZ41RW5yXDzltF4gWhsUinevFQQd4xYsmmqP" +
        "ywIDAQAB"

    private val SIGNATURE_SIZE = 256 // RSA-2048 签名固定 256 字节

    /** 读取设备序列号；不可读或为空返回 null */
    fun readSn(): String? = try {
        File("/data/misc/bbksn").readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * 校验激活码。
     * @param activationCode 服务器签发的激活码
     * @param sn 本机序列号
     */
    fun verify(activationCode: String, sn: String): Boolean = try {
        val payload = Base64.decode(
            activationCode.trim(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        if (payload.size < 2 + SIGNATURE_SIZE) {
            false
        } else {
            val len = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            if (len <= 0 || len > 128 || payload.size != 2 + len + SIGNATURE_SIZE) {
                false
            } else {
                val snInCode = String(payload, 2, len, Charsets.UTF_8)
                val sig = payload.copyOfRange(2 + len, payload.size)
                if (snInCode != sn) {
                    false
                } else {
                    val pub = KeyFactory.getInstance("RSA").generatePublic(
                        X509EncodedKeySpec(Base64.decode(PUB_KEY_B64, Base64.DEFAULT))
                    )
                    val verifier = Signature.getInstance("SHA256withRSA")
                    verifier.initVerify(pub)
                    verifier.update(sn.toByteArray(Charsets.UTF_8))
                    verifier.verify(sig)
                }
            }
        }
    } catch (_: Exception) {
        false
    }
}
