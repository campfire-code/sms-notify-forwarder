import json
import sys
import unittest
from datetime import timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import push  # noqa: E402

TZ = timezone(timedelta(hours=8))
T = 1_792_843_200_000  # 2026-10-24 12:00:00 UTC，上海时间 20:00:00
SMS = push.ForwardMessage("10086", "您的验证码是123456", T)
TEXT = "【短信】来自 10086\n您的验证码是123456\n2026-10-24 20:00:00"


def ch(kind, url, secret=""):
    return push.Channel("1", kind, "n", True, url, secret)


# 以下期望值与安卓端 SignersTest / RequestBuilderTest 使用同一组数据
class SignTests(unittest.TestCase):
    def test_dingtalk(self):
        self.assertEqual(push.dingtalk_sign(1577262236757, "this is secret"),
                         "hmPWwU%2B7lVdm3ZZz0r9tSfx0L4Q26jWOZr9%2BGs6EZQM%3D")

    def test_feishu(self):
        self.assertEqual(push.feishu_sign(1599360473, "this is secret"), "/eYKrXQBzTjUZJ3s6NX16VxGbTwmK7AEs8/FnvC1CSw=")


class BuildTests(unittest.TestCase):
    def test_wecom(self):
        url, body = push.build_request(ch("WECOM", "https://example.test/send?key=K"), SMS, T, TZ)
        self.assertEqual(url, "https://example.test/send?key=K")
        self.assertEqual(json.loads(body), {"msgtype": "text", "text": {"content": TEXT}})

    def test_wecom_truncates_to_2048_bytes(self):
        long = push.ForwardMessage("a", "字" * 2000, T)
        _, body = push.build_request(ch("WECOM", "https://example.test/x"), long, T, TZ)
        content = json.loads(body)["text"]["content"]
        self.assertLessEqual(len(content.encode("utf-8")), 2048)
        self.assertTrue(content.endswith("…"))

    def test_dingtalk_unsigned_keeps_url(self):
        url, body = push.build_request(ch("DINGTALK", "https://example.test/robot?access_token=T"), SMS, T, TZ)
        self.assertEqual(url, "https://example.test/robot?access_token=T")
        self.assertEqual(json.loads(body)["text"]["content"], TEXT)

    def test_dingtalk_signed(self):
        url, body = push.build_request(
            ch("DINGTALK", "https://example.test/robot?access_token=T", "this is secret"), SMS, 1577262236757, TZ)
        self.assertEqual(url, "https://example.test/robot?access_token=T&timestamp=1577262236757"
                              "&sign=hmPWwU%2B7lVdm3ZZz0r9tSfx0L4Q26jWOZr9%2BGs6EZQM%3D")
        self.assertNotIn("sign", body)

    def test_feishu_unsigned(self):
        _, body = push.build_request(ch("FEISHU", "https://example.test/hook/H"), SMS, T, TZ)
        data = json.loads(body)
        self.assertEqual(data, {"msg_type": "text", "content": {"text": TEXT}})

    def test_feishu_signed_uses_seconds(self):
        _, body = push.build_request(ch("FEISHU", "https://example.test/hook/H", "this is secret"), SMS, 1599360473_000, TZ)
        data = json.loads(body)
        self.assertEqual(data["timestamp"], "1599360473")
        self.assertEqual(data["sign"], "/eYKrXQBzTjUZJ3s6NX16VxGbTwmK7AEs8/FnvC1CSw=")

    def test_custom_body_fields(self):
        _, body = push.build_request(ch("CUSTOM", "http://example.test/hook"), SMS, T, TZ)
        self.assertEqual(json.loads(body), {
            "from": "10086", "content": "您的验证码是123456",
            "receivedAt": "2026-10-24T20:00:00+08:00", "source": "sms", "app": "",
        })


class ResponseTests(unittest.TestCase):
    def test_results(self):
        self.assertTrue(push.check_response("WECOM", 200, '{"errcode":0,"errmsg":"ok"}')[0])
        self.assertFalse(push.check_response("WECOM", 200, '{"errcode":93000,"errmsg":"x"}')[0])
        self.assertTrue(push.check_response("DINGTALK", 200, '{"errcode":0}')[0])
        self.assertFalse(push.check_response("DINGTALK", 200, '{"errcode":310000,"errmsg":"sign not match"}')[0])
        self.assertTrue(push.check_response("FEISHU", 200, '{"code":0}')[0])
        self.assertTrue(push.check_response("FEISHU", 200, '{"StatusCode":0}')[0])
        self.assertFalse(push.check_response("FEISHU", 200, '{"code":19021,"msg":"sign match fail"}')[0])
        self.assertTrue(push.check_response("CUSTOM", 204, "")[0])
        self.assertFalse(push.check_response("CUSTOM", 500, "")[0])
        self.assertFalse(push.check_response("WECOM", 200, "<html>")[0])
        self.assertEqual(push.check_response("WECOM", 502, "")[1], "HTTP 502")


class RuleTests(unittest.TestCase):
    def test_no_rules_forwards_all(self):
        self.assertTrue(push.should_forward([], "1", "x"))
        self.assertTrue(push.should_forward([push.Rule(enabled=True)], "1", "x"))  # 空规则无效

    def test_any_rule_matches(self):
        rules = [push.Rule(keywordContains="ORDER"), push.Rule(senderContains="10086")]
        self.assertTrue(push.should_forward(rules, "10010", "ORDER-1"))
        self.assertTrue(push.should_forward(rules, "10086", "hi"))
        self.assertFalse(push.should_forward(rules, "10010", "hello"))

    def test_conditions_within_rule_all_required(self):
        rule = push.Rule(senderContains="10086", keywordContains="验证码")
        self.assertFalse(push.rule_matches(rule, "10086", "余额"))
        self.assertTrue(push.rule_matches(rule, "10086", "验证码 1"))

    def test_regex_and_bad_regex(self):
        self.assertTrue(push.rule_matches(push.Rule(regex=r"\d{6}"), "a", "code 123456"))
        self.assertFalse(push.rule_matches(push.Rule(regex="(["), "a", "x"))

    def test_disabled_rule_ignored(self):
        self.assertTrue(push.should_forward([push.Rule(enabled=False, keywordContains="zzz")], "a", "b"))


class MaskTests(unittest.TestCase):
    def test_mask_codes(self):
        self.assertEqual(push.mask_codes("验证码123456, 1234567890, 12"), "验证码******, 1234567890, 12")

    def test_mask_secret(self):
        self.assertEqual(push.mask_secret("short"), "******")
        self.assertEqual(push.mask_secret("abcdefgh0123456789wxyz"), "abcdefgh******wxyz")


class SendTests(unittest.TestCase):
    def test_send_hides_network_details(self):
        def boom(url, body):
            raise OSError("secret-host.example")
        ok, detail = push.send(ch("WECOM", "https://secret-host.example/x"), SMS, T, boom, TZ)
        self.assertFalse(ok)
        self.assertNotIn("secret-host", detail)

    def test_send_rejects_bad_url(self):
        self.assertFalse(push.send(ch("WECOM", "ftp://x"), SMS, T, lambda u, b: (200, ""), TZ)[0])
        self.assertFalse(push.send(ch("WECOM", ""), SMS, T, lambda u, b: (200, ""), TZ)[0])

    def test_parse_channels_rejects_unknown_type(self):
        with self.assertRaises(ValueError):
            push.parse_channels([{"type": "sms"}])
        self.assertEqual(push.parse_channels([{"type": "wecom", "url": " u "}])[0].type, "WECOM")


if __name__ == "__main__":
    unittest.main()
