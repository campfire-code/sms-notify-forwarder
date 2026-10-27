#!/usr/bin/env python3
"""电脑端短信接收器：只读 USB 串口，解析短信后按 config.json 转发到企业微信 / 钉钉 / 飞书机器人或自建地址。

只读：不向设备写任何指令、不发短信、不删设备里的数据。
用法见 hardware/README.md。常用命令：
    python3 sms_receiver.py --config config.json            # 持续监听串口
    python3 sms_receiver.py --config config.json --test     # 向所有已启用通道发一条测试消息
    python3 sms_receiver.py --config config.json --replay frames.txt   # 不接硬件，按行回放短信帧
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import select
import sys
import time
from pathlib import Path
from typing import Callable, Optional

import push
import smsparse

DEFAULT_PORT_GLOB = "/dev/cu.usbserial-*"
DEFAULT_BAUD = 115200
QUIET_FRAME_SECONDS = 0.7
MAX_BUFFER = 64 * 1024
MAX_LOG_BYTES = 2 * 1024 * 1024
SEEN_LIMIT = 200
# 失败重试：首次发送失败后再试 3 次，间隔 10 秒起逐次翻倍（与安卓应用一致）
RETRY_DELAYS = (10, 20, 40)


def load_config(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        "channels": push.parse_channels(data.get("channels", [])),
        "rules": push.parse_rules(data.get("rules", [])),
        "serial": data.get("serial", {}) or {},
    }


class Receiver:
    """收到一帧 -> 解析 -> 去重 -> 规则过滤 -> 逐个通道发送，失败按 RETRY_DELAYS 重试。"""

    def __init__(self, config: dict, home: Path, post: push.HttpPost = push.http_post,
                 clock: Callable[[], float] = time.time, echo: Callable[[str], None] = lambda text: print(text, flush=True)):
        self.channels = [c for c in config["channels"] if c.enabled and c.url]
        self.rules = config["rules"]
        self.home = home
        self.post = post
        self.clock = clock
        self.echo = echo
        self.pending: list = []  # {channel, msg, attempts, due}
        home.mkdir(parents=True, exist_ok=True)
        self.state_path = home / "state.json"
        self.log_path = home / "receiver.log"

    # ----- 日志与状态（日志里的验证码一律打码，不写地址和密钥） -----
    def log(self, event: str, **extra) -> None:
        if self.log_path.exists() and self.log_path.stat().st_size >= MAX_LOG_BYTES:
            rotated = self.log_path.with_suffix(".log.1")
            if rotated.exists():
                rotated.unlink()
            self.log_path.replace(rotated)
        record = {"at": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(self.clock())), "event": event, **extra}
        with self.log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.echo(f"[{record['at']}] {event} " + " ".join(f"{k}={v}" for k, v in extra.items()))

    def _seen(self) -> list:
        try:
            data = json.loads(self.state_path.read_text(encoding="utf-8"))
            return [x for x in data.get("seen", []) if isinstance(x, str)]
        except (OSError, ValueError, AttributeError):
            return []

    def _remember(self, digest: str) -> None:
        seen = ([digest] + [x for x in self._seen() if x != digest])[:SEEN_LIMIT]
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"seen": seen}), encoding="utf-8")
        tmp.replace(self.state_path)

    # ----- 处理一帧 -----
    def handle_frame(self, raw: bytes) -> str:
        """返回处理结果：duplicate / decode_failed / filtered / no_channel / queued。"""
        digest = hashlib.sha256(raw).hexdigest()
        if digest in self._seen():
            self.log("重复帧已忽略", digest=digest[:12])
            return "duplicate"
        parsed = smsparse.parse_message(raw)
        self._remember(digest)
        if not parsed:
            self.log("解码失败", bytes=len(raw), digest=digest[:12])
            return "decode_failed"
        sender = parsed.get("from") or smsparse.DEFAULT_SENDER
        text = parsed["text"]
        preview = push.mask_codes(text)[:40]
        if not push.should_forward(self.rules, sender, text):
            self.log("未命中规则，不转发", sender=sender, preview=preview)
            return "filtered"
        if not self.channels:
            self.log("没有已启用的通道", sender=sender, preview=preview)
            return "no_channel"
        msg = push.ForwardMessage(sender, text, int(self.clock() * 1000))
        for channel in self.channels:
            self.pending.append({"channel": channel, "msg": msg, "attempts": 0, "due": 0.0})
        self.log("收到短信", sender=sender, format=parsed.get("format", ""), preview=preview)
        self.tick()
        return "queued"

    def send_test(self) -> bool:
        msg = push.ForwardMessage("测试", "这是一条测试消息", int(self.clock() * 1000))
        all_ok = bool(self.channels)
        for channel in self.channels:
            ok, detail = push.send(channel, msg, int(self.clock() * 1000), self.post)
            self.log("测试发送", channel=channel.name, result="成功" if ok else "失败", detail=detail)
            all_ok = all_ok and ok
        return all_ok

    def tick(self) -> None:
        """发送所有到期的待发项；失败的排到下一次重试。"""
        now = self.clock()
        for item in list(self.pending):
            if item["due"] > now:
                continue
            item["attempts"] += 1
            channel, msg = item["channel"], item["msg"]
            ok, detail = push.send(channel, msg, int(now * 1000), self.post)
            if ok:
                self.pending.remove(item)
                self.log("转发成功", channel=channel.name, attempts=item["attempts"])
            elif item["attempts"] > len(RETRY_DELAYS):
                self.pending.remove(item)
                self.log("转发失败，已放弃", channel=channel.name, detail=detail, attempts=item["attempts"])
            else:
                item["due"] = now + RETRY_DELAYS[item["attempts"] - 1]
                self.log("转发失败，稍后重试", channel=channel.name, detail=detail, attempts=item["attempts"])


# ---------- 串口（只读） ----------

def find_port(serial_cfg: dict) -> str:
    explicit = str(serial_cfg.get("port", "")).strip()
    if explicit:
        return explicit if Path(explicit).exists() or os.name == "nt" else ""
    matches = sorted(glob.glob(serial_cfg.get("portGlob") or DEFAULT_PORT_GLOB))
    return matches[0] if matches else ""


def open_posix(port: str, baud: int) -> int:
    import fcntl
    import struct
    import termios

    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    baud_constant = getattr(termios, f"B{baud}", None)
    custom_baud = baud_constant is None
    if custom_baud:
        baud_constant = termios.B38400
    attrs = termios.tcgetattr(fd)
    attrs[0] = termios.IGNPAR
    attrs[1] = 0
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[3] = 0
    attrs[4] = baud_constant
    attrs[5] = baud_constant
    attrs[6][termios.VMIN] = 0
    attrs[6][termios.VTIME] = 1
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    if custom_baud:
        fcntl.ioctl(fd, 0x80085402, struct.pack("Q", baud))  # macOS 的 IOSSIOSPEED
    try:
        fcntl.ioctl(fd, termios.TIOCMSET, struct.pack("I", termios.TIOCM_DTR | termios.TIOCM_RTS))
    except OSError:
        pass
    termios.tcflush(fd, termios.TCIFLUSH)
    return fd


def listen(receiver: Receiver, serial_cfg: dict) -> None:
    baud = int(serial_cfg.get("baud") or DEFAULT_BAUD)
    missing_logged = None
    while True:
        port = find_port(serial_cfg)
        if not port:
            if missing_logged is None or time.monotonic() - missing_logged >= 300:
                receiver.log("没有找到 USB 短信设备")
                missing_logged = time.monotonic()
            _idle(receiver, 3)
            continue
        try:
            _read_port(receiver, port, baud)
        except (OSError, RuntimeError) as error:
            receiver.log("串口连接失败", port=port, error=str(error))
            _idle(receiver, 3)


def _idle(receiver: Receiver, seconds: float) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        receiver.tick()
        time.sleep(0.25)


def _read_port(receiver: Receiver, port: str, baud: int) -> None:
    receiver.log("已连接串口", port=port, baud=baud)
    buffer = bytearray()
    last_byte_at = 0.0
    if os.name == "nt":
        # Windows 没有 termios，需要先 pip install pyserial（未在真机上验证）
        try:
            import serial  # type: ignore
        except ImportError:
            raise RuntimeError("Windows 上需要先安装 pyserial：pip install pyserial")
        handle = serial.Serial(port, baud, timeout=0.25)
        read: Callable[[], bytes] = lambda: handle.read(8192)
        close = handle.close
    else:
        fd = open_posix(port, baud)

        def read() -> bytes:
            ready, _, _ = select.select([fd], [], [], 0.25)
            return os.read(fd, 8192) if ready else b""

        close = lambda: os.close(fd)
    try:
        while True:
            chunk = read()
            now = time.monotonic()
            if chunk:
                buffer.extend(chunk)
                if len(buffer) > MAX_BUFFER:
                    del buffer[:-MAX_BUFFER]
                last_byte_at = now
            if buffer and now - last_byte_at >= QUIET_FRAME_SECONDS:
                frame = bytes(buffer)
                buffer.clear()
                receiver.handle_frame(frame)
            receiver.tick()
    finally:
        close()


def replay(receiver: Receiver, path: Path) -> None:
    """按行回放：每个非空行当作一帧，行内的 \\n（反斜杠加 n）还原成换行。回放结束后等待重试队列处理完。"""
    for line in path.read_bytes().splitlines():
        if line.strip():
            receiver.handle_frame(line.replace(b"\\n", b"\n"))
    while receiver.pending:
        time.sleep(0.5)
        receiver.tick()


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(description="USB 短信接收器：转发到企业微信 / 钉钉 / 飞书机器人或自建地址")
    parser.add_argument("--config", default="config.json", help="配置文件路径，默认 config.json")
    parser.add_argument("--home", default="", help="运行数据目录（日志、去重记录），默认在配置文件旁的 data 目录")
    parser.add_argument("--test", action="store_true", help="向所有已启用通道发一条测试消息后退出")
    parser.add_argument("--replay", default="", help="不接硬件：把文件里每一行当作一条短信帧回放（行内的 \\n 表示换行）")
    args = parser.parse_args(argv)

    config_path = Path(args.config).expanduser()
    if not config_path.exists():
        print(f"找不到配置文件 {config_path}，请先复制 config.example.json 并填写。", file=sys.stderr)
        return 2
    try:
        config = load_config(config_path)
    except (ValueError, OSError) as error:
        print(f"配置文件有误：{error}", file=sys.stderr)
        return 2
    try:
        os.umask(0o077)
    except OSError:
        pass
    home = Path(args.home).expanduser() if args.home else config_path.resolve().parent / "data"
    receiver = Receiver(config, home)
    if not receiver.channels:
        print("没有已启用且填了地址的通道，请检查 config.json。", file=sys.stderr)
        return 2
    if args.test:
        return 0 if receiver.send_test() else 1
    if args.replay:
        replay(receiver, Path(args.replay))
        return 0
    receiver.log("接收器已启动", readOnly=True, channels=len(receiver.channels))
    try:
        listen(receiver, config["serial"])
    except KeyboardInterrupt:
        receiver.log("接收器已停止")
    return 0


if __name__ == "__main__":
    sys.exit(main())
