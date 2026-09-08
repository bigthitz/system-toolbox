package com.system.toolbox.core

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** 激活校验结果 */
enum class VerifyResult { SUCCESS, EXPIRED, INVALID }

/**
 * 设备激活（公私钥签名 + 授权时间）。
 *
 * - 设备序列号：读取 /data/misc/bbksn（uid.system 有权限）
 * - 激活码 = Base64URL( 2字节大端SN长度 + SN + 8字节大端过期时间戳(秒) + RSA-2048签名 )
 * - 签名内容 = SN原文 + 过期时间字节（SHA256withRSA）
 * - 校验：SN 与本机一致 && 签名有效 && 未过期
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

    private const val SIGNATURE_SIZE = 256 // RSA-2048 签名固定 256 字节
    private const val TIME_SIZE = 8         // 过期时间戳：8 字节大端（秒）

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
    fun verify(activationCode: String, sn: String): VerifyResult = try {
        val payload = Base64.decode(
            activationCode.trim(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        if (payload.size < 2 + TIME_SIZE + SIGNATURE_SIZE) {
            VerifyResult.INVALID
        } else {
            val snLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            if (snLen <= 0 || snLen > 128 ||
                payload.size != 2 + snLen + TIME_SIZE + SIGNATURE_SIZE
            ) {
                VerifyResult.INVALID
            } else {
                val snInCode = String(payload, 2, snLen, Charsets.UTF_8)
                val timeOff = 2 + snLen
                var expire = 0L
                for (i in 0 until TIME_SIZE) {
                    expire = (expire shl 8) or (payload[timeOff + i].toLong() and 0xFF)
                }
                val sig = payload.copyOfRange(timeOff + TIME_SIZE, payload.size)

                if (snInCode != sn) {
                    VerifyResult.INVALID
                } else {
                    val pub = KeyFactory.getInstance("RSA").generatePublic(
                        X509EncodedKeySpec(Base64.decode(PUB_KEY_B64, Base64.DEFAULT))
                    )
                    val verifier = Signature.getInstance("SHA256withRSA")
                    verifier.initVerify(pub)
                    verifier.update(sn.toByteArray(Charsets.UTF_8))
                    for (i in 0 until TIME_SIZE) {
                        verifier.update(payload[timeOff + i])
                    }
                    val sigOk = verifier.verify(sig)
                    when {
                        !sigOk -> VerifyResult.INVALID
                        System.currentTimeMillis() / 1000L > expire -> VerifyResult.EXPIRED
                        else -> VerifyResult.SUCCESS
                    }
                }
            }
        }
    } catch (_: Exception) {
        VerifyResult.INVALID
    }
}
