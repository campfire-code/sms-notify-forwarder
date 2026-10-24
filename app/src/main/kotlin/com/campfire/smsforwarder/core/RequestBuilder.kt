package com.campfire.smsforwarder.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 按各平台官方文档构造请求。字段名与格式见 README「核实过的官方文档」。 */
object RequestBuilder {
    /** 企业微信文本消息 text.content 上限 2048 字节，超出时截断。 */
    const val WECOM_MAX_BYTES = 2048

    fun build(channel: Channel, msg: ForwardMessage, nowMillis: Long = System.currentTimeMillis()): HttpRequestSpec =
        when (channel.type) {
            ChannelType.WECOM -> HttpRequestSpec(channel.url, wecomBody(msg))
            ChannelType.DINGTALK -> dingtalk(channel, msg, nowMillis)
            ChannelType.FEISHU -> HttpRequestSpec(channel.url, feishuBody(msg, channel.secret, nowMillis / 1000))
            ChannelType.CUSTOM -> HttpRequestSpec(channel.url, customBody(msg))
        }

    /** 即时通讯机器人里看到的文本。 */
    fun displayText(msg: ForwardMessage): String {
        val head = when (msg.source) {
            Source.SMS -> "【短信】来自 ${msg.from}"
            Source.NOTIFICATION -> "【通知】${msg.app}" + if (msg.from.isNotBlank()) " · ${msg.from}" else ""
        }
        return "$head\n${msg.content}\n${formatTime(msg.receivedAt)}"
    }

    fun wecomBody(msg: ForwardMessage): String {
        val text = truncateBytes(displayText(msg), WECOM_MAX_BYTES)
        return obj {
            put("msgtype", "text")
            put("text", buildJsonObject { put("content", text) })
        }
    }

    private fun dingtalk(channel: Channel, msg: ForwardMessage, nowMillis: Long): HttpRequestSpec {
        val body = obj {
            put("msgtype", "text")
            put("text", buildJsonObject { put("content", displayText(msg)) })
        }
        if (channel.secret.isBlank()) return HttpRequestSpec(channel.url, body)
        val sign = Signers.dingTalkSign(nowMillis, channel.secret)
        val sep = if (channel.url.contains('?')) "&" else "?"
        return HttpRequestSpec("${channel.url}${sep}timestamp=$nowMillis&sign=$sign", body)
    }

    fun feishuBody(msg: ForwardMessage, secret: String, timestampSeconds: Long): String = obj {
        if (secret.isNotBlank()) {
            put("timestamp", timestampSeconds.toString())
            put("sign", Signers.feishuSign(timestampSeconds, secret))
        }
        put("msg_type", "text")
        put("content", buildJsonObject { put("text", displayText(msg)) })
    }

    /** 自定义 Webhook：字段固定为 from / content / receivedAt / source / app。 */
    fun customBody(msg: ForwardMessage): String = obj {
        put("from", msg.from)
        put("content", msg.content)
        put("receivedAt", formatTime(msg.receivedAt, iso = true))
        put("source", msg.source.wire)
        put("app", msg.app)
    }

    private inline fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject(block))

    /** iso=true 输出 ISO-8601（带时区偏移），否则输出便于阅读的本地时间。 */
    fun formatTime(millis: Long, iso: Boolean = false, tz: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat(if (iso) "yyyy-MM-dd'T'HH:mm:ssXXX" else "yyyy-MM-dd HH:mm:ss", Locale.US)
        f.timeZone = tz
        return f.format(Date(millis))
    }

    fun truncateBytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var used = 0
        for (cp in text.codePoints().toArray()) {
            val s = String(Character.toChars(cp))
            val n = s.toByteArray(Charsets.UTF_8).size
            if (used + n > maxBytes - 3) break
            sb.append(s); used += n
        }
        return "$sb…"
    }
}

/** 判断各平台返回内容是否表示成功。 */
object ResponseChecker {
    data class Result(val ok: Boolean, val detail: String)

    fun check(type: ChannelType, httpCode: Int, body: String): Result {
        if (httpCode !in 200..299) return Result(false, "HTTP $httpCode")
        if (type == ChannelType.CUSTOM) return Result(true, "HTTP $httpCode")
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Result(false, "返回内容不是 JSON")
        fun num(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        fun str(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return when (type) {
            ChannelType.WECOM ->
                if (num("errcode") == 0L) Result(true, "ok") else Result(false, "errcode=${num("errcode")} ${str("errmsg")}")
            ChannelType.DINGTALK -> {
                // 钉钉返回 errcode/errmsg；较新的接口返回 code/msg，两种都认
                val code = num("errcode") ?: num("code")
                if (code == 0L) Result(true, "ok") else Result(false, "错误码 $code ${str("errmsg").ifEmpty { str("msg") }}")
            }
            ChannelType.FEISHU -> {
                val code = num("code") ?: num("StatusCode")
                if (code == 0L) Result(true, "ok") else Result(false, "code=$code ${str("msg")}")
            }
            ChannelType.CUSTOM -> Result(true, "ok")
        }
    }
}
