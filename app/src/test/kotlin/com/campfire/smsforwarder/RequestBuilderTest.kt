package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.ChannelType
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.RequestBuilder
import com.campfire.smsforwarder.core.Source
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RequestBuilderTest {
    // 2026-10-24 12:00:00 UTC
    private val t = 1_792_843_200_000L
    private val sms = ForwardMessage("10086", "您的验证码是123456", t, Source.SMS)
    private val notif = ForwardMessage("张三", "你好", t, Source.NOTIFICATION, "微信")

    @Before fun tz() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun ch(type: ChannelType, url: String, secret: String = "") = Channel("1", type, "n", true, url, secret)

    @Test fun 企业微信_text消息() {
        val spec = RequestBuilder.build(ch(ChannelType.WECOM, "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=K"), sms, t)
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=K", spec.url)
        val o = obj(spec.body)
        assertEquals("text", o["msgtype"]!!.jsonPrimitive.content)
        val content = o["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("【短信】来自 10086\n您的验证码是123456\n2026-10-24 20:00:00", content)
        assertEquals(setOf("msgtype", "text"), o.keys)
    }

    @Test fun 企业微信_超长内容按2048字节截断() {
        val long = ForwardMessage("a", "字".repeat(2000), t, Source.SMS)
        val content = obj(RequestBuilder.wecomBody(long))["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(content.toByteArray().size <= 2048)
    }

    @Test fun 钉钉_未加签时地址不变() {
        val spec = RequestBuilder.build(ch(ChannelType.DINGTALK, "https://oapi.dingtalk.com/robot/send?access_token=T"), sms, t)
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=T", spec.url)
        val o = obj(spec.body)
        assertEquals("text", o["msgtype"]!!.jsonPrimitive.content)
        assertEquals("【短信】来自 10086\n您的验证码是123456\n2026-10-24 20:00:00",
            o["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun 钉钉_加签时地址带timestamp和sign() {
        val spec = RequestBuilder.build(
            ch(ChannelType.DINGTALK, "https://oapi.dingtalk.com/robot/send?access_token=T", "this is secret"), sms, 1577262236757L,
        )
        assertEquals(
            "https://oapi.dingtalk.com/robot/send?access_token=T&timestamp=1577262236757" +
                "&sign=hmPWwU%2B7lVdm3ZZz0r9tSfx0L4Q26jWOZr9%2BGs6EZQM%3D",
            spec.url,
        )
        assertFalse(spec.body.contains("sign"))
    }

    @Test fun 飞书_未开签名() {
        val spec = RequestBuilder.build(ch(ChannelType.FEISHU, "https://open.feishu.cn/open-apis/bot/v2/hook/H"), sms, t)
        val o = obj(spec.body)
        assertEquals("text", o["msg_type"]!!.jsonPrimitive.content)
        assertEquals("【短信】来自 10086\n您的验证码是123456\n2026-10-24 20:00:00",
            o["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("sign"))
        assertFalse(o.containsKey("timestamp"))
    }

    @Test fun 飞书_签名放在请求体里_timestamp为秒() {
        val spec = RequestBuilder.build(ch(ChannelType.FEISHU, "https://open.feishu.cn/open-apis/bot/v2/hook/H", "this is secret"), sms, 1599360473_000L)
        val o = obj(spec.body)
        assertEquals("1599360473", o["timestamp"]!!.jsonPrimitive.content)
        assertEquals("/eYKrXQBzTjUZJ3s6NX16VxGbTwmK7AEs8/FnvC1CSw=", o["sign"]!!.jsonPrimitive.content)
        assertEquals("text", o["msg_type"]!!.jsonPrimitive.content)
        assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/H", spec.url)
    }

    @Test fun 自定义Webhook_字段固定为五个_短信() {
        val o = obj(RequestBuilder.build(ch(ChannelType.CUSTOM, "http://10.0.2.2:8000/hook"), sms, t).body)
        assertEquals(setOf("from", "content", "receivedAt", "source", "app"), o.keys)
        assertEquals("10086", o["from"]!!.jsonPrimitive.content)
        assertEquals("您的验证码是123456", o["content"]!!.jsonPrimitive.content)
        assertEquals("2026-10-24T20:00:00+08:00", o["receivedAt"]!!.jsonPrimitive.content)
        assertEquals("sms", o["source"]!!.jsonPrimitive.content)
        assertEquals("", o["app"]!!.jsonPrimitive.content)
    }

    @Test fun 自定义Webhook_通知() {
        val o = obj(RequestBuilder.customBody(notif))
        assertEquals("notification", o["source"]!!.jsonPrimitive.content)
        assertEquals("微信", o["app"]!!.jsonPrimitive.content)
        assertEquals("张三", o["from"]!!.jsonPrimitive.content)
    }

    @Test fun 特殊字符被正确转义() {
        val m = ForwardMessage("a", "引号\"和换行\n和反斜杠\\", t, Source.SMS)
        val o = obj(RequestBuilder.customBody(m))
        assertEquals("引号\"和换行\n和反斜杠\\", o["content"]!!.jsonPrimitive.content)
    }

    @Test fun 通知的展示文本包含应用名() {
        assertEquals("【通知】微信 · 张三\n你好\n2026-10-24 20:00:00", RequestBuilder.displayText(notif))
    }
}
