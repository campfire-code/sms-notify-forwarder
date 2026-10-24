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
            return ResponseChecker.Result(false, "地址格式不正确")
        }
        return try {
            client.newCall(req).execute().use { resp ->
                ResponseChecker.check(channel.type, resp.code, resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            ResponseChecker.Result(false, "网络错误：${reason(e)}")
        }
    }

    /** 把常见网络异常翻成中文，其余统一写「连接失败」。 */
    private fun reason(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "找不到服务器，请检查地址和网络"
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "无法连接到服务器"
        is javax.net.ssl.SSLException -> "安全连接失败"
        else -> "连接失败"
    }
}
