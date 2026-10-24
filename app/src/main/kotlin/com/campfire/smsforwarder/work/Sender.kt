package com.campfire.smsforwarder.work

import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.HttpRequestSpec
import com.campfire.smsforwarder.core.RequestBuilder
import com.campfire.smsforwarder.core.ResponseChecker
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 发送一次请求，只连接用户配置的 Webhook 地址。 */
object Sender {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 结果里的 detail 不含 URL 和密钥。 */
    fun send(channel: Channel, msg: ForwardMessage): ResponseChecker.Result {
        val spec: HttpRequestSpec = try {
            RequestBuilder.build(channel, msg)
        } catch (e: Exception) {
            return ResponseChecker.Result(false, "构造请求失败")
        }
        val req = try {
            Request.Builder().url(spec.url).post(spec.body.toRequestBody(JSON)).build()
        } catch (e: IllegalArgumentException) {
            return ResponseChecker.Result(false, "Webhook 地址格式不正确")
        }
        return try {
            client.newCall(req).execute().use { resp ->
                ResponseChecker.check(channel.type, resp.code, resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            ResponseChecker.Result(false, "网络错误：${e.javaClass.simpleName}")
        }
    }
}
