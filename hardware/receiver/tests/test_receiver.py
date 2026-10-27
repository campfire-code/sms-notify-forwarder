import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import push  # noqa: E402
import sms_receiver  # noqa: E402


class Clock:
    def __init__(self):
        self.now = 1_792_843_200.0

    def __call__(self):
        return self.now


def make(tmp, post, rules=None, channels=None, clock=None):
    config = {
        "channels": channels if channels is not None else
        [push.Channel("1", "WECOM", "群A", True, "https://example.test/send?key=K")],
        "rules": rules or [],
        "serial": {},
    }
    return sms_receiver.Receiver(config, Path(tmp), post, clock or Clock(), echo=lambda s: None)


OK = (200, '{"errcode":0}')
FRAME = "来源：10086\n内容：登录验证码 654321".encode("utf-8")


class ReceiverTests(unittest.TestCase):
    def test_forward_and_dedupe(self):
        with tempfile.TemporaryDirectory() as tmp:
            sent = []
            r = make(tmp, lambda u, b: (sent.append((u, b)), OK)[1])
            self.assertEqual(r.handle_frame(FRAME), "queued")
            self.assertEqual(len(sent), 1)
            self.assertIn("654321", json.loads(sent[0][1])["text"]["content"])
            self.assertEqual(r.handle_frame(FRAME), "duplicate")
            self.assertEqual(len(sent), 1)
            # 重新创建接收器后，去重记录仍在
            r2 = make(tmp, lambda u, b: OK)
            self.assertEqual(r2.handle_frame(FRAME), "duplicate")

    def test_rule_filters(self):
        with tempfile.TemporaryDirectory() as tmp:
            sent = []
            r = make(tmp, lambda u, b: (sent.append(b), OK)[1], rules=[push.Rule(keywordContains="ORDER")])
            self.assertEqual(r.handle_frame(FRAME), "filtered")
            self.assertEqual(r.handle_frame("来源：10010\n内容：ORDER-1 已发货".encode()), "queued")
            self.assertEqual(len(sent), 1)

    def test_retry_then_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            clock, calls = Clock(), []

            def post(u, b):
                calls.append(1)
                return (500, "") if len(calls) < 3 else OK

            r = make(tmp, post, clock=clock)
            r.handle_frame(FRAME)
            self.assertEqual(len(calls), 1)
            r.tick()
            self.assertEqual(len(calls), 1)  # 未到时间
            clock.now += 10
            r.tick()
            self.assertEqual(len(calls), 2)
            clock.now += 19
            r.tick()
            self.assertEqual(len(calls), 2)  # 第二次重试要等 20 秒
            clock.now += 1
            r.tick()
            self.assertEqual(len(calls), 3)
            self.assertEqual(r.pending, [])

    def test_gives_up_after_four_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            clock, calls = Clock(), []
            r = make(tmp, lambda u, b: (calls.append(1), (500, ""))[1], clock=clock)
            r.handle_frame(FRAME)
            for _ in range(10):
                clock.now += 100
                r.tick()
            self.assertEqual(len(calls), 4)
            self.assertEqual(r.pending, [])

    def test_decode_failure_and_no_channel(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = make(tmp, lambda u, b: OK)
            self.assertEqual(r.handle_frame(bytes.fromhex("9e8660869ee69e66669e18187e181818")), "decode_failed")
            r2 = make(tmp + "/b", lambda u, b: OK, channels=[])
            self.assertEqual(r2.handle_frame(FRAME), "no_channel")

    def test_log_masks_codes_and_hides_url(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = make(tmp, lambda u, b: OK)
            r.handle_frame(FRAME)
            log = (Path(tmp) / "receiver.log").read_text(encoding="utf-8")
            self.assertNotIn("654321", log)
            self.assertIn("******", log)
            self.assertNotIn("example.test", log)

    def test_multiple_channels_each_get_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            urls = []
            chans = [push.Channel("1", "WECOM", "A", True, "https://a.test/x"),
                     push.Channel("2", "CUSTOM", "B", True, "http://b.test/y"),
                     push.Channel("3", "FEISHU", "C", False, "https://c.test/z")]
            r = make(tmp, lambda u, b: (urls.append(u), (200, '{"errcode":0}'))[1], channels=chans)
            r.handle_frame(FRAME)
            self.assertEqual(urls, ["https://a.test/x", "http://b.test/y"])  # 停用的通道不发

    def test_send_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            r = make(tmp, lambda u, b: OK)
            self.assertTrue(r.send_test())
            r2 = make(tmp + "/b", lambda u, b: (500, ""))
            self.assertFalse(r2.send_test())


class ConfigTests(unittest.TestCase):
    def test_example_config_loads(self):
        config = sms_receiver.load_config(HERE.parent / "config.example.json")
        self.assertEqual(config["channels"][0].type, "WECOM")
        self.assertEqual(config["channels"][0].url, "")  # 示例里不写任何地址
        self.assertEqual(config["serial"]["baud"], 115200)

    def test_main_requires_config_and_channel(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(sms_receiver.main(["--config", str(Path(tmp) / "none.json")]), 2)
            # 示例配置里通道没填地址，应拒绝启动
            self.assertEqual(sms_receiver.main(["--config", str(HERE.parent / "config.example.json"),
                                                "--home", tmp]), 2)

    def test_replay_end_to_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            frames = Path(tmp) / "frames.txt"
            frames.write_bytes("来源：10086\\n内容：登录验证码 654321\n".encode())
            r = make(tmp, lambda u, b: OK)
            sms_receiver.replay(r, frames)
            log = (Path(tmp) / "receiver.log").read_text(encoding="utf-8")
            self.assertIn("转发成功", log)
            self.assertIn('"sender": "10086"', log)


class HygieneTests(unittest.TestCase):
    """硬件目录里不应出现写死的地址、密钥或内网 IP（不写出任何具体旧值，只用通用模式）。"""

    def test_no_hardcoded_endpoints(self):
        root = HERE.parents[1]
        patterns = [
            re.compile(r"\b(?:10|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d+\.\d+(?:\.\d+)?\b"),
            re.compile(r"127\.0\.0\.1:\d+"),
            re.compile(r"/Users/|C:\\\\Users"),
            re.compile(r"(?:key|access_token)=[0-9A-Za-z-]{16,}"),
            re.compile(r"AppSecret|\bTOKEN\s*="),
        ]
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".py", ".lua", ".json", ".md"} or "__pycache__" in path.parts:
                continue
            if path.name.startswith("test_"):
                continue
            text = path.read_text(encoding="utf-8")
            for pattern in patterns:
                self.assertIsNone(pattern.search(text), f"{path.name} 匹配 {pattern.pattern}")


if __name__ == "__main__":
    unittest.main()
