PROJECT = "smsfwd"
VERSION = "001.999.000"

local config = require("config")
local QUEUE_SLOT_PREFIX = "smsfwd_q_"
local SEQUENCE_KEY = "smsfwd_seq_v1"
local DEFAULT_HTTP_TIMEOUT_MS = 15000
local DEFAULT_MAX_QUEUE_SIZE = 20
local DEFAULT_MAX_BACKOFF_MS = 300000
local MAX_STORAGE_SLOTS = 20
local FSKV_VALUE_LIMIT_BYTES = 4096

local smsQueue = {}
local sequence = 0

local function safeString(value)
    if value == nil then
        return ""
    end
    return tostring(value)
end

local function queueLimit()
    local configured = tonumber(config.MAX_QUEUE_SIZE) or DEFAULT_MAX_QUEUE_SIZE
    return math.min(math.max(math.floor(configured), 1), MAX_STORAGE_SLOTS)
end

local function slotKey(slot)
    return QUEUE_SLOT_PREFIX .. string.format("%02d", slot)
end

local function persistItem(item)
    local raw = json.encode(item)
    if type(raw) ~= "string" or #raw >= FSKV_VALUE_LIMIT_BYTES then
        log.error("smsfwd", "queue_item_too_large", item._slot or -1, type(raw) == "string" and #raw or -1)
        return false
    end
    local saved = fskv.set(slotKey(item._slot), raw)
    if saved ~= true then
        log.error("smsfwd", "queue_persist_failed", item._slot)
        return false
    end
    return true
end

local function deleteItem(item)
    if item and item._slot then
        fskv.del(slotKey(item._slot))
    end
end

local function restoreQueue()
    fskv.init()
    sequence = tonumber(fskv.get(SEQUENCE_KEY)) or 0
    for slot = 1, MAX_STORAGE_SLOTS do
        local raw = fskv.get(slotKey(slot))
        if type(raw) == "string" and raw ~= "" then
            local ok, decoded = pcall(json.decode, raw)
            if ok and type(decoded) == "table" and decoded.eventId and decoded.text then
                decoded._slot = slot
                decoded.order = tonumber(decoded.order) or 0
                table.insert(smsQueue, decoded)
                sequence = math.max(sequence, decoded.order)
            else
                fskv.del(slotKey(slot))
                log.warn("smsfwd", "invalid_queue_slot_removed", slot)
            end
        end
    end
    table.sort(smsQueue, function(left, right)
        return (tonumber(left.order) or 0) < (tonumber(right.order) or 0)
    end)
    fskv.set(SEQUENCE_KEY, sequence)
    log.info("smsfwd", "restored_queue", #smsQueue)
end

local function nextSequence()
    sequence = sequence + 1
    if fskv.set(SEQUENCE_KEY, sequence) ~= true then
        log.error("smsfwd", "sequence_persist_failed")
    end
    return sequence
end

local function findFreeSlot()
    local used = {}
    for _, item in ipairs(smsQueue) do
        used[tonumber(item._slot)] = true
    end
    for slot = 1, MAX_STORAGE_SLOTS do
        if not used[slot] then
            return slot
        end
    end
    return nil
end

local function enqueueSms(sender, text)
    local maxQueueSize = queueLimit()
    while #smsQueue >= maxQueueSize do
        local removed = table.remove(smsQueue, 1)
        deleteItem(removed)
        log.warn("smsfwd", "queue_full_drop_oldest")
    end
    local slot = findFreeSlot()
    if not slot then
        log.error("smsfwd", "queue_no_free_slot")
        return false
    end
    local order = nextSequence()
    local item = {
        eventId = safeString(config.DEVICE_ID) .. "-" .. safeString(os.time()) .. "-" .. safeString(order),
        sender = safeString(sender),
        text = safeString(text),
        receivedStamp = safeString(os.time()),
        attempts = 0,
        order = order,
        _slot = slot,
    }
    local persisted = persistItem(item)
    table.insert(smsQueue, item)
    log.info("smsfwd", "sms_queued", #smsQueue)
    return persisted
end

local CHANNEL_TYPES = { wecom = true, dingtalk = true, feishu = true, custom = true }
local WECOM_MAX_BYTES = 2048

-- 按 UTF-8 字节数截断，不切断汉字
local function truncateBytes(text, maxBytes)
    if #text <= maxBytes then
        return text
    end
    local cut = maxBytes - 3
    while cut > 0 do
        local byte = text:byte(cut + 1)
        if byte and byte >= 0x80 and byte < 0xC0 then
            cut = cut - 1
        else
            break
        end
    end
    return text:sub(1, cut) .. "…"
end

local function displayText(item)
    return "【短信】来自 " .. item.sender .. "\n" .. item.text .. "\n" ..
        os.date("%Y-%m-%d %H:%M:%S", tonumber(item.receivedStamp) or os.time())
end

-- 请求体格式与应用里的同名通道一致
local function buildBody(item)
    local kind = config.CHANNEL_TYPE
    if kind == "wecom" then
        return json.encode({ msgtype = "text", text = { content = truncateBytes(displayText(item), WECOM_MAX_BYTES) } })
    elseif kind == "dingtalk" then
        return json.encode({ msgtype = "text", text = { content = displayText(item) } })
    elseif kind == "feishu" then
        return json.encode({ msg_type = "text", content = { text = displayText(item) } })
    end
    return json.encode({
        from = item.sender,
        content = item.text,
        receivedAt = os.date("!%Y-%m-%dT%H:%M:%SZ", tonumber(item.receivedStamp) or os.time()),
        source = "sms",
        app = "",
    })
end

-- 按各平台返回判断是否成功：企业微信、钉钉看 errcode，飞书看 code，自定义地址只看 2xx
local function parseServerAccepted(status, body)
    if type(status) ~= "number" or status < 200 or status > 299 then
        return false
    end
    local kind = config.CHANNEL_TYPE
    if kind == "custom" then
        return true
    end
    if type(body) ~= "string" or body == "" then
        return false
    end
    local ok, response = pcall(json.decode, body)
    if not ok or type(response) ~= "table" then
        return false
    end
    if kind == "feishu" then
        local code = response.code
        if code == nil then
            code = response.StatusCode
        end
        return tonumber(code) == 0
    end
    local code = response.errcode
    if code == nil and kind == "dingtalk" then
        code = response.code
    end
    return tonumber(code) == 0
end

local function postQueueHead(item)
    local headers = { ["Content-Type"] = "application/json" }
    local timeout = tonumber(config.HTTP_TIMEOUT_MS) or DEFAULT_HTTP_TIMEOUT_MS
    local status, _, body = http.request(
        "POST",
        config.WEBHOOK_URL,
        headers,
        buildBody(item),
        { timeout = timeout }
    ).wait()
    return parseServerAccepted(status, body), status
end

local function retryDelay(attempts)
    local maxBackoff = tonumber(config.MAX_BACKOFF_MS) or DEFAULT_MAX_BACKOFF_MS
    local exponent = math.min(math.max(attempts - 1, 0), 8)
    return math.min(2000 * (2 ^ exponent), maxBackoff)
end

local function forwardTask()
    while true do
        if #smsQueue == 0 then
            sys.wait(1000)
        elseif not socket.adapter(socket.dft()) then
            log.warn("smsfwd", "wait_network", #smsQueue)
            sys.waitUntil("IP_READY", 1000)
        else
            local item = smsQueue[1]
            item.attempts = (tonumber(item.attempts) or 0) + 1
            persistItem(item)
            local ok, status = postQueueHead(item)
            if ok then
                table.remove(smsQueue, 1)
                deleteItem(item)
                log.info("smsfwd", "forward_success", status, #smsQueue)
            else
                local delay = retryDelay(item.attempts)
                log.warn("smsfwd", "forward_retry", status or -999, item.attempts, delay)
                sys.wait(delay)
            end
        end
    end
end

local function receiveTask()
    while true do
        local _, sender, text = sys.waitUntil("SMS_INC")
        if sender and text then
            -- 不把发送方、短信正文、验证码或上传密钥写入串口日志。
            local messageBytes = safeString(text):len()
            log.info("smsfwd", "sms_received", messageBytes)
            enqueueSms(sender, text)
        end
    end
end

local function statusTask()
    while true do
        log.info("smsfwd", "status", rtos.bsp(), #smsQueue)
        sys.wait(60000)
    end
end

assert(CHANNEL_TYPES[config.CHANNEL_TYPE], "CHANNEL_TYPE 只能是 wecom、dingtalk、feishu、custom")
assert(type(config.WEBHOOK_URL) == "string" and config.WEBHOOK_URL:match("^https?://"), "WEBHOOK_URL 必须以 https:// 或 http:// 开头")
assert(not config.WEBHOOK_URL:find("替换为"), "WEBHOOK_URL 还没有填写")

restoreQueue()
wdt.init(60000)
sys.timerLoopStart(wdt.feed, 10000)
sys.taskInit(receiveTask)
sys.taskInit(forwardTask)
sys.taskInit(statusTask)
sys.run()
