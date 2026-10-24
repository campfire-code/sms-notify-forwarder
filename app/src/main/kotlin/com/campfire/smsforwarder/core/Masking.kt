package com.campfire.smsforwarder.core

object Masking {
    private val CODE = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** 把 4～8 位连续数字（验证码）替换为等长的 *，其余内容不动。 */
    fun maskCodes(text: String): String = CODE.replace(text) { "*".repeat(it.value.length) }

    /** Webhook 地址 / 密钥只露出前后几位，其余用 *** 代替；太短则全部隐藏。 */
    fun maskSecret(value: String, head: Int = 8, tail: Int = 4): String {
        if (value.isEmpty()) return ""
        if (value.length <= head + tail + 4) return "******"
        return value.take(head) + "******" + value.takeLast(tail)
    }
}
