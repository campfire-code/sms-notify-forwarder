package com.campfire.smsforwarder.core

import kotlinx.serialization.Serializable

/** 来源：短信或应用通知（自定义 Webhook 的 source 字段取值就是 wire）。 */
enum class Source(val wire: String, val label: String) {
    SMS("sms", "短信"),
    NOTIFICATION("notification", "通知"),
}

/** 一条待转发的消息。 */
data class ForwardMessage(
    val from: String,
    val content: String,
    val receivedAt: Long,
    val source: Source,
    /** 通知来源的应用名；短信为空字符串。 */
    val app: String = "",
)

enum class ChannelType(val label: String) {
    WECOM("企业微信群机器人"),
    DINGTALK("钉钉自定义机器人"),
    FEISHU("飞书自定义机器人"),
    CUSTOM("自定义 Webhook"),
}

@Serializable
data class Channel(
    val id: String,
    val type: ChannelType,
    val name: String,
    val enabled: Boolean = true,
    val url: String = "",
    /** 钉钉「加签」/ 飞书「签名校验」的密钥，可为空（表示未开启签名）。自定义 Webhook 不使用。 */
    val secret: String = "",
)

/**
 * 一条过滤规则。规则内填写的条件必须同时满足；多条规则命中任意一条即转发。
 * 三个条件都为空的规则视为无效（忽略）。
 */
@Serializable
data class Rule(
    val id: String,
    val enabled: Boolean = true,
    val senderContains: String = "",
    val keywordContains: String = "",
    val regex: String = "",
)

/** 发出的 HTTP 请求（只含地址和 JSON 请求体）。 */
data class HttpRequestSpec(val url: String, val body: String)
