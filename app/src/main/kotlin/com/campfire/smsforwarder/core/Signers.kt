package com.campfire.smsforwarder.core

import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 签名算法，按各平台官方文档实现：
 * - 钉钉「加签」：timestamp 为毫秒；待签名字符串 = timestamp + "\n" + 密钥；
 *   以密钥为 key、待签名字符串为消息做 HmacSHA256，再 Base64，再 URL 编码（UTF-8），
 *   timestamp 与 sign 作为查询参数拼到 Webhook 地址后。
 * - 飞书「签名校验」：timestamp 为秒；待签名字符串 = timestamp + "\n" + 密钥；
 *   以待签名字符串为 key、空消息做 HmacSHA256，再 Base64；timestamp 与 sign 放进请求体。
 */
object Signers {
    private const val HMAC = "HmacSHA256"

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(message)
    }

    /** 返回已 URL 编码的钉钉签名。 */
    fun dingTalkSign(timestampMillis: Long, secret: String): String {
        val stringToSign = "$timestampMillis\n$secret"
        val raw = hmac(secret.toByteArray(Charsets.UTF_8), stringToSign.toByteArray(Charsets.UTF_8))
        return URLEncoder.encode(Base64.getEncoder().encodeToString(raw), "UTF-8")
    }

    fun feishuSign(timestampSeconds: Long, secret: String): String {
        val stringToSign = "$timestampSeconds\n$secret"
        val raw = hmac(stringToSign.toByteArray(Charsets.UTF_8), ByteArray(0))
        return Base64.getEncoder().encodeToString(raw)
    }
}
