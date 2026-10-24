package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.ChannelType
import com.campfire.smsforwarder.core.ResponseChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCheckerTest {
    private fun ok(t: ChannelType, code: Int, body: String) = ResponseChecker.check(t, code, body).ok

    @Test fun 企业微信() {
        assertTrue(ok(ChannelType.WECOM, 200, """{"errcode":0,"errmsg":"ok"}"""))
        assertFalse(ok(ChannelType.WECOM, 200, """{"errcode":93000,"errmsg":"invalid webhook url"}"""))
    }
    @Test fun 钉钉() {
        assertTrue(ok(ChannelType.DINGTALK, 200, """{"errcode":0,"errmsg":"ok"}"""))
        assertFalse(ok(ChannelType.DINGTALK, 200, """{"errcode":310000,"errmsg":"sign not match"}"""))
    }
    @Test fun 飞书() {
        assertTrue(ok(ChannelType.FEISHU, 200, """{"StatusCode":0,"StatusMessage":"success","code":0,"data":{},"msg":"success"}"""))
        assertFalse(ok(ChannelType.FEISHU, 200, """{"code":19021,"msg":"sign match fail or timestamp is not within one hour from current time"}"""))
    }
    @Test fun 自定义只看HTTP状态码() {
        assertTrue(ok(ChannelType.CUSTOM, 204, ""))
        assertFalse(ok(ChannelType.CUSTOM, 500, "x"))
    }
    @Test fun HTTP错误和非JSON() {
        assertFalse(ok(ChannelType.WECOM, 404, ""))
        assertFalse(ok(ChannelType.WECOM, 200, "<html>"))
    }
}
