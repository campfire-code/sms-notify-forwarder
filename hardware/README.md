# 硬件方案：用 SIM 卡硬件收短信

不想让手机一直开着、或者手机装不了后台应用时，可以用一块插着 SIM 卡的硬件收短信，再由它（或电脑）把短信转发到你自己的企业微信群、钉钉群、飞书群或自建地址。转发格式、过滤规则、失败重试和安卓应用一致。

> 短信里常有验证码、银行通知等敏感信息。只转发到你自己可控的群或服务器。配置文件里的机器人地址等同密码，不要提交到仓库、不要发给别人。

## 什么时候用手机，什么时候用硬件

| 情况 | 建议 |
|---|---|
| 手机一直在身边、能保持联网，系统不会清理后台 | 用手机（安卓应用），最省事，还能转发 App 通知 |
| 手机后台常被系统杀掉，漏转 | 用硬件 |
| 想用一张专用 SIM 卡收验证码，不占用日常手机 | 用硬件 |
| 需要转发微信、支付宝等 App 的通知 | 只能用手机，硬件只收短信 |
| 要 24 小时无人值守 | 硬件更合适，但要自己保证供电和网络 |
| 钉钉「加签」、飞书「签名校验」 | 手机应用和电脑端接收器都支持；4G 模组固件不支持，需用「自定义关键词」 |

## 两种硬件方式

1. **USB 串口短信接收器 + 电脑端接收器**：硬件通过 USB 接电脑，电脑上运行 `receiver/sms_receiver.py`，它只读串口、解析短信并推送。支持 JSON、键值对、纯文本、SMS-DELIVER PDU 四种输出格式。
2. **Air780EPM 4G 模组 + 固件**：模组自己联网，把短信直接推送到机器人或自建地址，不需要电脑。固件在 `firmware/`。

## 方式一：电脑端接收器

### 准备

- Python 3.9 及以上，没有第三方依赖。
- 一个插着 SIM 卡、能通过 USB 串口输出收到的短信的接收器。接收器脚本是按 UT80ZBM-T 这类接收器写的；该设备具体的输出格式和需要的设置**未核实**，脚本能解析的格式见上文，解析不了的帧会记为「解码失败」。
- 在电脑上能看到串口：macOS 默认匹配 `/dev/cu.usbserial-*`；Linux 常见 `/dev/ttyUSB0`，把配置里的 `port` 改成它（Linux 上未实测）；Windows 填 `COM3` 这样的名字，并先 `pip install pyserial`（Windows 路径未在真机上验证）。波特率默认 115200。

### 配置

```bash
cd hardware/receiver
cp config.example.json config.json   # 然后编辑 config.json
```

`channels` 和 `rules` 的写法与应用里的通道、规则一致：

| 字段 | 含义 |
|---|---|
| `type` | `WECOM` 企业微信群机器人、`DINGTALK` 钉钉自定义机器人、`FEISHU` 飞书自定义机器人、`CUSTOM` 自定义回调地址 |
| `name`、`enabled` | 通道名称、开关 |
| `url` | 你自己的机器人地址或回调地址（示例文件里留空，不填就不会启动） |
| `secret` | 钉钉「加签」或飞书「签名校验」的密钥，没开就留空 |
| 规则 `senderContains`、`keywordContains`、`regex` | 同一条规则里填了的条件都要满足，多条规则命中任一条就转发；没有有效规则就转发全部 |

各平台的请求格式、加签算法、返回判断见 [../README.md](../README.md) 的「配置转发通道」，这里与应用完全相同。`serial` 里可以固定 `port`，或用 `portGlob` 自动查找。

### 运行

```bash
python3 sms_receiver.py --config config.json --test     # 先向所有已启用通道发一条测试消息
python3 sms_receiver.py --config config.json            # 持续监听串口
```

- 只读：不向设备写任何指令、不发短信、不删设备数据。
- 日志和去重记录在配置文件旁的 `data/` 目录（可用 `--home` 改）。日志里的验证码（4 到 8 位数字）打码，不记录地址和密钥。发到机器人的内容是原文。
- 发送失败自动再试 3 次（一共最多 4 次），间隔 10、20、40 秒；重试队列在内存里，程序退出会丢失。
- 同一帧原始数据只处理一次（记录最近 200 条）。设备若每次发出完全相同的字节，后面的会被当成重复忽略。
- 开机自启要自己用系统的计划任务或服务管理配置，这里没有提供，也没有验证过。
- 不接硬件时用回放测试：`python3 sms_receiver.py --config config.json --replay ../../samples/hardware-frames.txt`（文件里每行一帧，`\n` 表示换行）。

## 方式二：Air780EPM 4G 模组

### 型号与资料

- 模组：合宙 Air780EPM（4G Cat.1，运行 LuatOS）。固件脚本用到的接口（`SMS_INC` 短信事件、`fskv` 存储、`http.request`）来自固件脚本本身，在真实模组上的行为**未核实**。
- 官方文档入口：<https://docs.openluat.com/air780epm/luatos/>。烧录页（<https://docs.openluat.com/air780epm/luatos/firmware/burn/>）写明有两个工具：Luatools（开发调试用）和合宙量产烧录工具（量产用）。

### 接线

本工具没有接线资料，以下**未核实**，请以合宙官方硬件文档为准：

- 需要插入可收短信的 SIM 卡、接好 4G 天线、用 USB 供电并连接电脑下载程序。具体的引脚、供电电压和进入下载模式的方法请查官方「硬件开发资料」，本工具不提供。

### 刷固件步骤

1. 复制 `firmware/config.example.lua` 为 `firmware/config.lua`，填写 `CHANNEL_TYPE`（`wecom`、`dingtalk`、`feishu`、`custom`）和 `WEBHOOK_URL`。不填写固件会拒绝启动。
2. 钉钉、飞书的机器人「安全设置」选「自定义关键词」，加上关键词「短信」（固件发出的消息以「【短信】」开头）。固件不支持加签和签名校验。
3. 用 Luatools 把 `main.lua` 和 `config.lua` 下载进模组，底层固件版本按官方「Air780EPM 固件版本」页选择（本工具没有指定具体版本，**未核实**；`main.lua` 里的 `VERSION` 只是脚本自己的版本号）。Luatools 的具体操作（选择文件、下载按钮）**未核实**，见官方 Luatools 使用说明。
4. 模组上电后看 Luatools 日志，收到短信会出现 `sms_received`、`sms_queued`、`forward_success`。日志里不输出号码和短信正文。

### 固件行为

- 收到短信先存入模组的闪存队列（最多 20 条，满了丢弃最旧的），再逐条推送；失败按 2 秒起翻倍退避重试（上限默认 5 分钟），断电重启后队列保留。因为队首失败会挡住后面的，地址填错时队列会堆积。
- 请求体与应用相同：`wecom` 用 `msgtype=text`（超过 2048 字节截断），`dingtalk` 用 `msgtype=text`，`feishu` 用 `msg_type=text`，`custom` 用 `from / content / receivedAt / source / app` 五个字段。企业微信、钉钉看返回的 `errcode`，飞书看 `code`，自定义地址只看 2xx。
- 消息里的时间取模组当前时间。模组时钟是否会自动校准**未核实**，若不准，消息里的时间会不准。自定义地址的 `receivedAt` 用 UTC（末尾 `Z`）。
- 允许 `http://` 地址（方便局域网自建服务），公网上的服务请用 `https://`。

## 验收用例

自动测试（电脑上，不联网）：

```bash
cd hardware/receiver
python3 -m unittest discover -s tests      # 期望：全部通过；装了 lupa 时多跑固件逻辑测试
```

不接硬件，用回放验证电脑端接收器：

1. 终端 1：`python3 samples/receiver.py 8765`（在仓库根目录）。
2. 终端 2：`python3 hardware/receiver/sms_receiver.py --config samples/hardware-config.json --home /tmp/hw-demo --replay samples/hardware-frames.txt`。预期：终端 1 依次收到 4 条 JSON，`content` 分别是商城验证码短信、快递 ORDER-1、银行动态码 246810、`TEST1234`，`source` 均为 `sms`；终端 2 的日志里验证码显示为 `******`。
3. 再运行一次第 2 步的命令：预期 4 行「重复帧已忽略」，终端 1 没有新请求。
4. 把配置里的 `rules` 改成 `[{"keywordContains":"ORDER"}]`，换一个 `--home` 再回放：只有快递那一条被转发，其余记为「未命中规则，不转发」。
5. 关掉终端 1 的接收服务再回放：日志出现「转发失败，稍后重试」，10 秒后再试，共最多 4 次后「转发失败，已放弃」。

需要硬件的用例（本次**未执行**，没有硬件）：

6. USB 接收器接电脑，`--test` 后群里收到测试消息；用另一部手机给 SIM 卡发一条短信，群里在几秒内收到「【短信】来自 …」。
7. 刷好 Air780EPM 后给 SIM 卡发短信，Luatools 日志出现 `forward_success`，群里收到消息；拔网线或断开 4G 后发短信，恢复后补发。
