package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.ChannelType
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.Source
import com.campfire.smsforwarder.work.Sender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用本地 MockWebServer 验证真正发出去的 HTTP 请求。 */
class SenderTest {
    private val msg = ForwardMessage("10086", "您的验证码是123456", 1_792_843_200_000L, Source.SMS)

    @Test fun 自定义Webhook发出POST_JSON() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val ch = Channel("1", ChannelType.CUSTOM, "t", true, server.url("/hook").toString())
            val r = Sender.send(ch, msg)
            assertTrue(r.detail, r.ok)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/hook", req.path)
            assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
            val o = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("您的验证码是123456", o["content"]!!.jsonPrimitive.content)
            assertEquals("sms", o["source"]!!.jsonPrimitive.content)
        }
    }

    @Test fun 服务器返回500判失败() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val r = Sender.send(Channel("1", ChannelType.CUSTOM, "t", true, server.url("/").toString()), msg)
            assertFalse(r.ok)
        }
    }

    @Test fun 钉钉业务错误码判失败() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"errcode":310000,"errmsg":"sign not match"}"""))
            server.start()
            val r = Sender.send(Channel("1", ChannelType.DINGTALK, "t", true, server.url("/robot/send?access_token=T").toString(), "SECabc"), msg)
            assertFalse(r.ok)
            val req = server.takeRequest()
            assertTrue(req.path!!.contains("timestamp=") && req.path!!.contains("sign="))
        }
    }

    @Test fun 地址格式错误不崩溃() {
        val r = Sender.send(Channel("1", ChannelType.CUSTOM, "t", true, "不是网址"), msg)
        assertFalse(r.ok)
    }
}
