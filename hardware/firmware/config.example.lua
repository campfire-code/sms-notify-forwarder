-- 复制为 config.lua，再填写你自己的机器人地址或接收地址。
-- config.lua 里有地址（等同密码），不要提交到仓库、不要发给别人。
return {
    -- 推送类型："wecom"（企业微信群机器人）、"dingtalk"（钉钉自定义机器人）、
    -- "feishu"（飞书自定义机器人）、"custom"（自建接收地址，请求体与应用里的「自定义回调地址」相同）
    CHANNEL_TYPE = "wecom",
    -- 机器人地址或自建接收地址。填写前这里保持"替换为"字样，固件会拒绝启动。
    WEBHOOK_URL = "https://替换为你的机器人地址",
    -- 钉钉、飞书的机器人安全设置请选「自定义关键词」并添加「短信」。
    -- 本固件不支持钉钉「加签」、飞书「签名校验」；需要加签请改用电脑端接收器。
    DEVICE_ID = "air780epm-01",
    HTTP_TIMEOUT_MS = 15000,
    MAX_QUEUE_SIZE = 20,
    MAX_BACKOFF_MS = 300000,
}
