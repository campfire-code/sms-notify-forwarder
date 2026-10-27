"""短信帧解析：把串口收到的一段字节解析成 {from, text, ...}。

支持四种格式：JSON、中文或英文键值对、SMS-DELIVER PDU（十六进制）、纯文本。
只做解析，不碰串口，所以可以在任何系统上测试。
"""

from __future__ import annotations

import codecs
import json
import re

DEFAULT_SENDER = "USB短信设备"


def text_score(text: str) -> float:
    if not text:
        return -10.0
    useful = sum(ch.isprintable() or ch in "\r\n\t" for ch in text)
    replacements = text.count("�") + text.count("\ufffd")
    nul = text.count("\x00")
    chinese = len(re.findall(r"[\u4e00-\u9fff]", text))
    sms_markers = len(re.findall(r"验证码|校验码|短信|code|sender|from|phone|content", text, re.I))
    return useful / max(1, len(text)) - replacements * 0.25 - nul * 0.08 + min(chinese, 12) * 0.03 + sms_markers * 0.3


def decode_text(raw: bytes) -> tuple[str, str, float]:
    candidates: list[tuple[str, str, float]] = []
    for encoding in ("utf-8", "gb18030", "utf-16le", "utf-16be"):
        if encoding.startswith("utf-16"):
            has_matching_bom = (
                (encoding == "utf-16le" and raw.startswith(codecs.BOM_UTF16_LE))
                or (encoding == "utf-16be" and raw.startswith(codecs.BOM_UTF16_BE))
            )
            zero_ratio = raw.count(0) / max(1, len(raw))
            if not has_matching_bom and zero_ratio < 0.10:
                continue
        try:
            decoded = raw.decode(encoding)
        except UnicodeDecodeError:
            decoded = raw.decode(encoding, errors="replace")
        cleaned = decoded.replace("\ufeff", "").strip("\x00\r\n \t")
        candidates.append((cleaned, encoding, text_score(cleaned)))
    return max(candidates, key=lambda item: item[2])


GSM7 = (
    "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\x1bÆæßÉ"
    " !\"#¤%&'()*+,-./0123456789:;<=>?"
    "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
)


def semi_octet_number(data: bytes, digits: int) -> str:
    chars: list[str] = []
    for value in data:
        chars.extend((str(value & 0x0F), str((value >> 4) & 0x0F)))
    return "".join(chars)[:digits].replace("15", "")


def decode_gsm7(data: bytes, septet_count: int) -> str:
    bits = 0
    bit_count = 0
    result: list[str] = []
    for value in data:
        bits |= value << bit_count
        bit_count += 8
        while bit_count >= 7 and len(result) < septet_count:
            index = bits & 0x7F
            result.append(GSM7[index] if index < len(GSM7) else "?")
            bits >>= 7
            bit_count -= 7
    return "".join(result)


def decode_sms_deliver_pdu(hex_text: str) -> dict | None:
    compact = re.sub(r"[^0-9A-Fa-f]", "", hex_text)
    if len(compact) < 32 or len(compact) % 2:
        return None
    try:
        data = bytes.fromhex(compact)
        index = 0
        smsc_len = data[index]
        index += 1 + smsc_len
        first_octet = data[index]
        index += 1
        if first_octet & 0x03 != 0:
            return None
        sender_digits = data[index]
        index += 1
        sender_type = data[index]
        index += 1
        sender_bytes_len = (sender_digits + 1) // 2
        sender = semi_octet_number(data[index:index + sender_bytes_len], sender_digits)
        index += sender_bytes_len
        if sender_type == 0x91:
            sender = "+" + sender
        index += 1  # PID
        dcs = data[index]
        index += 1
        index += 7  # service-centre timestamp
        user_length = data[index]
        index += 1
        user_data = data[index:]
        if dcs & 0x0C == 0x08:
            text = user_data[:user_length].decode("utf-16be", errors="replace")
        elif dcs & 0x0C == 0x00:
            text = decode_gsm7(user_data, user_length)
        else:
            text = user_data[:user_length].decode("latin-1", errors="replace")
        return {"from": sender, "text": text.strip("\x00")}
    except (IndexError, ValueError):
        return None


def first_value(data: dict, keys: tuple[str, ...]) -> str:
    for key in keys:
        value = data.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return str(value).strip()
    return ""


def parse_message(raw: bytes) -> dict | None:
    text, encoding, score = decode_text(raw)

    # JSON is the safest vendor-neutral shape.
    for candidate in (text, *re.findall(r"\{.*?\}", text, re.S)):
        try:
            payload = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(payload, dict):
            body = first_value(payload, ("text", "message", "body", "sms", "content"))
            if body:
                return {
                    "from": first_value(payload, ("from", "sender", "phone", "address", "source")),
                    "text": body,
                    "deviceImei": first_value(payload, ("imei",)),
                    "deviceNumber": first_value(payload, ("number",)),
                    "encoding": encoding,
                    "format": "json",
                }

    # Some receivers emit a GSM PDU as one long hex line.
    for hex_candidate in re.findall(r"(?:[0-9A-Fa-f]{2}[\s:]*){16,}", text):
        decoded = decode_sms_deliver_pdu(hex_candidate)
        if decoded and decoded.get("text"):
            return {**decoded, "encoding": "pdu", "format": "sms-deliver-pdu"}

    # Common key/value output from small serial SMS receivers.
    body_match = re.search(
        r"(?:内容|短信|正文|content|message|body)\s*[:：=]\s*(.+)",
        text,
        re.I | re.S,
    )
    sender_match = re.search(
        r"(?:来源|发送人|号码|sender|from|phone|address)\s*[:：=]\s*([^,;\r\n]+)",
        text,
        re.I,
    )
    if body_match:
        return {
            "from": sender_match.group(1).strip() if sender_match else DEFAULT_SENDER,
            "text": body_match.group(1).strip(),
            "encoding": encoding,
            "format": "key-value",
        }

    cleaned = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text).strip()
    contains_useful_content = bool(re.search(r"[\u4e00-\u9fffA-Za-z0-9]{4,}", cleaned))
    if score >= 0.82 and contains_useful_content:
        return {
            "from": sender_match.group(1).strip() if sender_match else DEFAULT_SENDER,
            "text": cleaned,
            "encoding": encoding,
            "format": "plain-text",
        }
    return None
