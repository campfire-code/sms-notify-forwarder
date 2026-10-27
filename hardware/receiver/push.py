"""推送规则：与安卓应用的转发逻辑保持一致（请求格式、加签、过滤规则、返回判断、日志打码）。

对应安卓端源码：core/RequestBuilder.kt、core/Signers.kt、core/RuleMatcher.kt、core/Masking.kt。
配置里的字段名（id / type / name / enabled / url / secret，以及规则的 senderContains /
keywordContains / regex）与应用内保存的格式相同。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone, tzinfo
from typing import Callable, Optional

WECOM_MAX_BYTES = 2048
CHANNEL_TYPES = ("WECOM", "DINGTALK", "FEISHU", "CUSTOM")
CHANNEL_LABELS = {
    "WECOM": "企业微信群机器人",
    "DINGTALK": "钉钉自定义机器人",
    "FEISHU": "飞书自定义机器人",
    "CUSTOM": "自定义回调地址",
}


@dataclass
class Channel:
    id: str
    type: str
    name: str
    enabled: bool = True
    url: str = ""
    secret: str = ""


@dataclass
class Rule:
    id: str = ""
    enabled: bool = True
    senderContains: str = ""
    keywordContains: str = ""
    regex: str = ""


@dataclass
class ForwardMessage:
    sender: str
    content: str
    received_at_ms: int
    source: str = "sms"
    app: str = ""


def parse_channels(items: list) -> list:
    result = []
    for index, item in enumerate(items or []):
        kind = str(item.get("type", "")).upper()
        if kind not in CHANNEL_TYPES:
            raise ValueError(f"第 {index + 1} 个通道的 type 不认识，可选：{', '.join(CHANNEL_TYPES)}")
        result.append(Channel(
            id=str(item.get("id") or index + 1),
            type=kind,
            name=str(item.get("name") or CHANNEL_LABELS[kind]),
            enabled=bool(item.get("enabled", True)),
            url=str(item.get("url", "")).strip(),
            secret=str(item.get("secret", "")).strip(),
        ))
    return result


def parse_rules(items: list) -> list:
    return [
        Rule(
            id=str(item.get("id") or index + 1),
            enabled=bool(item.get("enabled", True)),
            senderContains=str(item.get("senderContains", "")),
            keywordContains=str(item.get("keywordContains", "")),
            regex=str(item.get("regex", "")),
        )
        for index, item in enumerate(items or [])
    ]


# ---------- 签名 ----------

def _hmac_b64(key: bytes, message: bytes) -> str:
    return base64.b64encode(hmac.new(key, message, hashlib.sha256).digest()).decode("ascii")


def dingtalk_sign(timestamp_ms: int, secret: str) -> str:
    """钉钉加签：key=密钥，消息=timestamp+换行+密钥，HmacSHA256 后 Base64，再 URL 编码。"""
    to_sign = f"{timestamp_ms}\n{secret}".encode("utf-8")
    return urllib.parse.quote_plus(_hmac_b64(secret.encode("utf-8"), to_sign))


def feishu_sign(timestamp_s: int, secret: str) -> str:
    """飞书签名校验：key=timestamp+换行+密钥，消息为空，HmacSHA256 后 Base64。"""
    return _hmac_b64(f"{timestamp_s}\n{secret}".encode("utf-8"), b"")


# ---------- 请求构造 ----------

def format_time(millis: int, iso: bool = False, tz: Optional[tzinfo] = None) -> str:
    stamp = millis / 1000
    moment = datetime.fromtimestamp(stamp, tz) if tz else datetime.fromtimestamp(stamp).astimezone()
    if iso:
        return moment.isoformat(timespec="seconds")
    return moment.strftime("%Y-%m-%d %H:%M:%S")


def display_text(msg: ForwardMessage, tz: Optional[tzinfo] = None) -> str:
    if msg.source == "notification":
        head = f"【通知】{msg.app}" + (f" · {msg.sender}" if msg.sender.strip() else "")
    else:
        head = f"【短信】来自 {msg.sender}"
    return f"{head}\n{msg.content}\n{format_time(msg.received_at_ms, tz=tz)}"


def truncate_bytes(text: str, max_bytes: int) -> str:
    if len(text.encode("utf-8")) <= max_bytes:
        return text
    out, used = [], 0
    for ch in text:
        n = len(ch.encode("utf-8"))
        if used + n > max_bytes - 3:
            break
        out.append(ch)
        used += n
    return "".join(out) + "…"


def _dump(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build_request(channel: Channel, msg: ForwardMessage, now_ms: int, tz: Optional[tzinfo] = None) -> tuple:
    """返回 (url, body)。"""
    text = display_text(msg, tz)
    if channel.type == "WECOM":
        return channel.url, _dump({"msgtype": "text", "text": {"content": truncate_bytes(text, WECOM_MAX_BYTES)}})
    if channel.type == "DINGTALK":
        body = _dump({"msgtype": "text", "text": {"content": text}})
        if not channel.secret:
            return channel.url, body
        sep = "&" if "?" in channel.url else "?"
        return f"{channel.url}{sep}timestamp={now_ms}&sign={dingtalk_sign(now_ms, channel.secret)}", body
    if channel.type == "FEISHU":
        payload = {}
        if channel.secret:
            seconds = now_ms // 1000
            payload["timestamp"] = str(seconds)
            payload["sign"] = feishu_sign(seconds, channel.secret)
        payload["msg_type"] = "text"
        payload["content"] = {"text": text}
        return channel.url, _dump(payload)
    return channel.url, _dump({
        "from": msg.sender,
        "content": msg.content,
        "receivedAt": format_time(msg.received_at_ms, iso=True, tz=tz),
        "source": msg.source,
        "app": msg.app,
    })


def check_response(kind: str, http_code: int, body: str) -> tuple:
    """判断各平台返回是否成功，返回 (ok, 说明)。"""
    if not 200 <= http_code <= 299:
        return False, f"HTTP {http_code}"
    if kind == "CUSTOM":
        return True, f"HTTP {http_code}"
    try:
        data = json.loads(body)
    except ValueError:
        data = None
    if not isinstance(data, dict):
        return False, "返回内容不是 JSON"

    def num(key):
        try:
            return int(str(data.get(key)).strip())
        except (TypeError, ValueError):
            return None

    if kind == "WECOM":
        ok = num("errcode") == 0
        return ok, "ok" if ok else f"errcode={num('errcode')} {data.get('errmsg', '')}"
    if kind == "DINGTALK":
        code = num("errcode") if num("errcode") is not None else num("code")
        return (True, "ok") if code == 0 else (False, f"错误码 {code} {data.get('errmsg') or data.get('msg') or ''}")
    code = num("code") if num("code") is not None else num("StatusCode")
    return (True, "ok") if code == 0 else (False, f"code={code} {data.get('msg', '')}")


# ---------- 规则 ----------

def _effective(rule: Rule) -> bool:
    return bool(rule.senderContains.strip() or rule.keywordContains.strip() or rule.regex.strip())


def rule_matches(rule: Rule, sender: str, content: str) -> bool:
    if rule.senderContains.strip() and rule.senderContains.strip() not in sender:
        return False
    if rule.keywordContains.strip() and rule.keywordContains.strip() not in content:
        return False
    if rule.regex.strip():
        try:
            if not re.search(rule.regex, content):
                return False
        except re.error:
            return False  # 正则写错的规则不命中
    return True


def should_forward(rules: list, sender: str, content: str) -> bool:
    """没有任何有效规则时转发全部；否则命中任一有效规则才转发。"""
    active = [r for r in rules if r.enabled and _effective(r)]
    if not active:
        return True
    return any(rule_matches(r, sender, content) for r in active)


# ---------- 打码 ----------

_CODE = re.compile(r"(?<!\d)\d{4,8}(?!\d)")


def mask_codes(text: str) -> str:
    return _CODE.sub(lambda m: "*" * len(m.group(0)), text)


def mask_secret(value: str, head: int = 8, tail: int = 4) -> str:
    if not value:
        return ""
    if len(value) <= head + tail + 4:
        return "******"
    return value[:head] + "******" + value[-tail:]


# ---------- 发送 ----------

HttpPost = Callable[[str, str], tuple]


def http_post(url: str, body: str) -> tuple:
    """真实发送，返回 (http 状态码, 返回正文)。网络错误抛 OSError。"""
    request = urllib.request.Request(
        url, data=body.encode("utf-8"), headers={"Content-Type": "application/json; charset=utf-8"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")


def send(channel: Channel, msg: ForwardMessage, now_ms: int, post: HttpPost = http_post,
         tz: Optional[tzinfo] = None) -> tuple:
    """发一次，返回 (ok, 说明)。说明里不含地址和密钥。"""
    if not channel.url:
        return False, "没有填写地址"
    if not channel.url.startswith(("http://", "https://")):
        return False, "地址格式不正确"
    url, body = build_request(channel, msg, now_ms, tz)
    try:
        code, text = post(url, body)
    except (OSError, ValueError) as error:
        return False, f"网络错误：{type(error).__name__}"
    return check_response(channel.type, code, text)
