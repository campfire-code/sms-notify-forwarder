package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.Signers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉钉、飞书官方文档只给了算法和代码、没有给出示例结果值，所以这里的期望值由独立实现（Python hmac/hashlib/base64/urllib）
 * 按官方文档算法算出：
 *   钉钉：HMAC-SHA256(key=密钥, msg=timestamp+"\n"+密钥) -> Base64 -> URL 编码
 *   飞书：HMAC-SHA256(key=timestamp+"\n"+密钥, msg=空) -> Base64
 */
class SignersTest {
    @Test fun 钉钉加签() {
        assertEquals(
            "hmPWwU%2B7lVdm3ZZz0r9tSfx0L4Q26jWOZr9%2BGs6EZQM%3D",
            Signers.dingTalkSign(1577262236757L, "this is secret"),
        )
    }

    @Test fun 飞书签名() {
        assertEquals("/eYKrXQBzTjUZJ3s6NX16VxGbTwmK7AEs8/FnvC1CSw=", Signers.feishuSign(1599360473L, "this is secret"))
    }

    @Test fun 钉钉签名中的加号等特殊字符被编码() {
        val s = Signers.dingTalkSign(1577262236757L, "this is secret")
        assert(!s.contains('+') && !s.contains('=') && !s.contains('/'))
    }
}
