import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import smsparse  # noqa: E402


def make_pdu(sender_digits: str, text: str) -> str:
    """独立构造一条 SMS-DELIVER PDU（UCS2 编码），用来验证解析器。"""
    padded = sender_digits + ("F" if len(sender_digits) % 2 else "")
    number = "".join(padded[i + 1] + padded[i] for i in range(0, len(padded), 2))
    body = text.encode("utf-16be")
    return ("00" + "04" + format(len(sender_digits), "02X") + "81" + number
            + "00" + "08" + "62019010000000" + format(len(body), "02X") + body.hex().upper())


class ParseTests(unittest.TestCase):
    def test_json_message(self):
        message = smsparse.parse_message(
            '{"imei":"demo-imei","number":"demo-device-number","source":"10690000","text":"您的验证码是 123456"}'.encode()
        )
        self.assertEqual(message["from"], "10690000")
        self.assertEqual(message["text"], "您的验证码是 123456")
        self.assertEqual(message["deviceNumber"], "demo-device-number")
        self.assertEqual(message["deviceImei"], "demo-imei")
        self.assertEqual(message["format"], "json")

    def test_chinese_key_value_message(self):
        message = smsparse.parse_message("来源：10086\n内容：登录验证码 654321".encode("gb18030"))
        self.assertEqual(message["from"], "10086")
        self.assertEqual(message["text"], "登录验证码 654321")
        self.assertEqual(message["format"], "key-value")

    def test_plain_text_test_code(self):
        message = smsparse.parse_message(b"CODEX1234")
        self.assertEqual(message["text"], "CODEX1234")
        self.assertEqual(message["format"], "plain-text")
        self.assertEqual(message["from"], smsparse.DEFAULT_SENDER)

    def test_binary_noise_is_rejected(self):
        self.assertIsNone(smsparse.parse_message(bytes.fromhex("9e8660869ee69e66669e18187e181818")))

    def test_pdu_ucs2(self):
        pdu = make_pdu("10690000", "【示例商城】验证码 246810")
        message = smsparse.parse_message(pdu.encode())
        self.assertIsNotNone(message)
        self.assertEqual(message["format"], "sms-deliver-pdu")
        self.assertEqual(message["from"], "10690000")
        self.assertEqual(message["text"], "【示例商城】验证码 246810")

    def test_pdu_international_sender_gets_plus(self):
        pdu = make_pdu("8613800000000", "hello world test").replace("0D81", "0D91", 1)
        message = smsparse.parse_message(pdu.encode())
        self.assertEqual(message["from"], "+8613800000000")

    def test_gsm7_decoding(self):
        # "hellohello" 的 GSM 7bit 打包结果
        self.assertEqual(smsparse.decode_gsm7(bytes.fromhex("E8329BFD4697D9EC37"), 10), "hellohello")

    def test_utf16_with_bom(self):
        raw = b"\xff\xfe" + "来源：10086\n内容：验证码 135790".encode("utf-16le")
        message = smsparse.parse_message(raw)
        self.assertEqual(message["text"], "验证码 135790")


if __name__ == "__main__":
    unittest.main()
