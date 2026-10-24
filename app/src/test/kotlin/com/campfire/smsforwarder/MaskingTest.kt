package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.Masking
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskingTest {
    @Test fun 六位验证码被打码() = assertEquals("您的验证码是******", Masking.maskCodes("您的验证码是123456"))
    @Test fun 四位和八位被打码() {
        assertEquals("码 **** 与 ********", Masking.maskCodes("码 1234 与 12345678"))
    }
    @Test fun 三位和九位不打码() {
        assertEquals("123 和 123456789", Masking.maskCodes("123 和 123456789"))
    }
    @Test fun 手机号等长数字串不被拆开() {
        assertEquals("电话 13800138000", Masking.maskCodes("电话 13800138000"))
    }
    @Test fun 紧贴中文的验证码也打码() = assertEquals("验证码****请查收", Masking.maskCodes("验证码8888请查收"))
    @Test fun 多个验证码() = assertEquals("***** 和 ******", Masking.maskCodes("12345 和 654321"))
    @Test fun 无数字原样返回() = assertEquals("你好", Masking.maskCodes("你好"))

    @Test fun 地址只显示前后几位() {
        val url = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abcd1234-5678-90ef"
        val m = Masking.maskSecret(url)
        assertEquals("https://******90ef", m)
        assert(!m.contains("webhook"))
    }
    @Test fun 太短的密钥全部隐藏() = assertEquals("******", Masking.maskSecret("SEC123", 2, 2))
    @Test fun 空值返回空() = assertEquals("", Masking.maskSecret(""))
}
