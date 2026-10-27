"""用 lupa（Lua 解释器的 Python 绑定）在电脑上跑固件逻辑：模拟 LuatOS 的 sys / http / fskv / json。
没装 lupa 时跳过（pip install lupa）。这只验证固件里的请求格式、返回判断、排队重试，
不代表真实模组上的行为，真机验证见 hardware/README.md 的验收用例。"""
import json
import unittest
from pathlib import Path

try:
    import lupa
    from lupa import LuaRuntime
except ImportError:  # pragma: no cover
    lupa = None

FIRMWARE = Path(__file__).resolve().parents[2] / "firmware"

HARNESS = r'''
tasks = {}
inbox = {}
calls = {}
store = {}
CONFIG = nil
sleeps = {}
package.preload["config"] = function() return CONFIG end
log = { info = function() end, warn = function() end, error = function() end }
fskv = {
  init = function() end,
  get = function(k) return store[k] end,
  set = function(k, v) store[k] = v return true end,
  del = function(k) store[k] = nil end,
}
json = { encode = py_encode, decode = py_decode }
rtos = { bsp = function() return "TEST" end }
wdt = { init = function() end, feed = function() end }
socket = { adapter = function() return true end, dft = function() return 1 end }
http = { request = function(method, url, headers, body, opts)
  calls[#calls + 1] = { method = method, url = url, body = body }
  local r = table.remove(responses, 1) or { 200, "" }
  return { wait = function() return r[1], {}, r[2] end }
end }
sys = {
  taskInit = function(f) tasks[#tasks + 1] = { co = coroutine.create(f), dead = false } end,
  timerLoopStart = function() end,
  wait = function(ms) sleeps[#sleeps + 1] = ms coroutine.yield("wait") end,
  waitUntil = function(name, ms)
    if name == "SMS_INC" then
      local m = table.remove(inbox, 1)
      if m then return true, m[1], m[2] end
      coroutine.yield("park")
    end
    coroutine.yield("wait")
    return false
  end,
  run = function() end,
}
function step(rounds)
  for _ = 1, rounds do
    for _, t in ipairs(tasks) do
      if not t.dead and coroutine.status(t.co) == "suspended" then
        local ok, res = coroutine.resume(t.co)
        assert(ok, res)
        if res == "park" then t.parked = true end
      end
    end
  end
end
function loadfw(src) return load(src, "main.lua") end
'''


def to_py(value):
    if lupa.lua_type(value) == "table":
        keys = list(value.keys())
        if keys and all(isinstance(k, int) for k in keys):
            return [to_py(value[k]) for k in sorted(keys)]
        return {k: to_py(v) for k, v in value.items()}
    return value


@unittest.skipIf(lupa is None, "未安装 lupa，跳过固件逻辑测试")
class FirmwareTests(unittest.TestCase):
    def boot(self, channel_type, responses, url="https://example.test/hook"):
        lua = LuaRuntime(unpack_returned_tuples=True)

        def py_encode(table):
            return json.dumps(to_py(table), ensure_ascii=False)

        def py_decode(text):
            def conv(v):
                if isinstance(v, dict):
                    return lua.table_from({k: conv(x) for k, x in v.items()})
                return v
            return conv(json.loads(text))

        lua.globals().py_encode = py_encode
        lua.globals().py_decode = py_decode
        lua.execute(HARNESS)
        g = lua.globals()
        g.CONFIG = lua.table_from({
            "CHANNEL_TYPE": channel_type, "WEBHOOK_URL": url, "DEVICE_ID": "dev",
            "MAX_QUEUE_SIZE": 3,
        })
        g.responses = lua.table_from([lua.table_from([code, body]) for code, body in responses])
        src = (FIRMWARE / "main.lua").read_text(encoding="utf-8")
        g.loadfw(src)()
        return lua

    def run_sms(self, lua, sender, text, rounds=6):
        g = lua.globals()
        g.inbox[len(g.inbox) + 1] = lua.table_from([sender, text])
        g.step(rounds)

    def calls(self, lua):
        return [to_py(c) for c in lua.globals().calls.values()]

    def test_wecom_body_and_success(self):
        lua = self.boot("wecom", [(200, '{"errcode":0,"errmsg":"ok"}')])
        self.run_sms(lua, "10086", "验证码 123456")
        calls = self.calls(lua)
        self.assertEqual(len(calls), 1)
        content = json.loads(calls[0]["body"])["text"]["content"]
        self.assertTrue(content.startswith("【短信】来自 10086\n验证码 123456\n"))
        self.assertEqual(json.loads(calls[0]["body"])["msgtype"], "text")

    def test_feishu_and_dingtalk_shapes(self):
        lua = self.boot("feishu", [(200, '{"code":0}')])
        self.run_sms(lua, "a", "hello")
        body = json.loads(self.calls(lua)[0]["body"])
        self.assertEqual(body["msg_type"], "text")
        self.assertIn("hello", body["content"]["text"])
        lua = self.boot("dingtalk", [(200, '{"errcode":0}')])
        self.run_sms(lua, "a", "hello")
        self.assertEqual(json.loads(self.calls(lua)[0]["body"])["msgtype"], "text")

    def test_custom_body_fields(self):
        lua = self.boot("custom", [(204, "")])
        self.run_sms(lua, "10086", "hi")
        body = json.loads(self.calls(lua)[0]["body"])
        self.assertEqual(set(body), {"from", "content", "receivedAt", "source", "app"})
        self.assertEqual((body["from"], body["content"], body["source"], body["app"]), ("10086", "hi", "sms", ""))
        self.assertRegex(body["receivedAt"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")

    def test_failure_keeps_item_and_retries(self):
        lua = self.boot("wecom", [(200, '{"errcode":93000}'), (500, ""), (200, '{"errcode":0}')])
        self.run_sms(lua, "a", "x", rounds=10)
        self.assertEqual(len(self.calls(lua)), 3)  # 两次失败后第三次成功
        sleeps = [int(v) for v in lua.globals().sleeps.values() if 1000 < v < 60000]
        self.assertEqual(sleeps[:2], [2000, 4000])  # 退避从 2 秒起翻倍

    def test_wecom_truncates_long_text_on_char_boundary(self):
        lua = self.boot("wecom", [(200, '{"errcode":0}')])
        self.run_sms(lua, "a", "字" * 1500)
        content = json.loads(self.calls(lua)[0]["body"])["text"]["content"]
        self.assertLessEqual(len(content.encode("utf-8")), 2048)
        content.encode("utf-8").decode("utf-8")  # 不会截断出半个汉字

    def test_refuses_unconfigured_url_and_bad_type(self):
        with self.assertRaises(Exception):
            self.boot("wecom", [], url="https://替换为你的机器人地址")
        with self.assertRaises(Exception):
            self.boot("sms", [])
        with self.assertRaises(Exception):
            self.boot("wecom", [], url="ftp://x")

    def test_no_hardcoded_address_in_firmware_files(self):
        for name in ("main.lua", "config.example.lua"):
            text = (FIRMWARE / name).read_text(encoding="utf-8")
            self.assertNotIn("example.invalid", text)
            self.assertNotRegex(text, r"https?://[^\"\s]*(?:key|token)=")


if __name__ == "__main__":
    unittest.main()
