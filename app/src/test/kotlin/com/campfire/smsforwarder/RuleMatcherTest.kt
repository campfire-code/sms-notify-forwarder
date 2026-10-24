package com.campfire.smsforwarder

import com.campfire.smsforwarder.core.Rule
import com.campfire.smsforwarder.core.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {
    private fun r(sender: String = "", kw: String = "", re: String = "", on: Boolean = true) =
        Rule("x", on, sender, kw, re)

    @Test fun 无规则转发全部() = assertTrue(RuleMatcher.shouldForward(emptyList(), "10086", "任意"))
    @Test fun 只有空规则等于无规则() = assertTrue(RuleMatcher.shouldForward(listOf(r()), "a", "b"))
    @Test fun 只有停用规则等于无规则() = assertTrue(RuleMatcher.shouldForward(listOf(r(kw = "验证码", on = false)), "a", "b"))
    @Test fun 发件号码包含() {
        val rules = listOf(r(sender = "1069"))
        assertTrue(RuleMatcher.shouldForward(rules, "106900123", "x"))
        assertFalse(RuleMatcher.shouldForward(rules, "10086", "x"))
    }
    @Test fun 关键词包含() {
        val rules = listOf(r(kw = "验证码"))
        assertTrue(RuleMatcher.shouldForward(rules, "a", "您的验证码是1"))
        assertFalse(RuleMatcher.shouldForward(rules, "a", "促销"))
    }
    @Test fun 正则() {
        val rules = listOf(r(re = "验证码\\D*\\d{6}"))
        assertTrue(RuleMatcher.shouldForward(rules, "a", "验证码是123456"))
        assertFalse(RuleMatcher.shouldForward(rules, "a", "验证码是1234"))
    }
    @Test fun 一条规则内多个条件需同时满足() {
        val rules = listOf(r(sender = "1069", kw = "订单"))
        assertTrue(RuleMatcher.shouldForward(rules, "1069x", "订单已发货"))
        assertFalse(RuleMatcher.shouldForward(rules, "1069x", "验证码"))
        assertFalse(RuleMatcher.shouldForward(rules, "10086", "订单已发货"))
    }
    @Test fun 多条规则命中任一即转发() {
        val rules = listOf(r(kw = "验证码"), r(sender = "95588"))
        assertTrue(RuleMatcher.shouldForward(rules, "95588", "余额变动"))
        assertTrue(RuleMatcher.shouldForward(rules, "x", "验证码"))
        assertFalse(RuleMatcher.shouldForward(rules, "x", "广告"))
    }
    @Test fun 非法正则不命中且不崩溃() {
        assertFalse(RuleMatcher.shouldForward(listOf(r(re = "([")), "a", "b"))
        assertFalse(RuleMatcher.isValidRegex("(["))
        assertTrue(RuleMatcher.isValidRegex("\\d+"))
    }
    @Test fun 判断规则是否有效() {
        assertEquals(false, RuleMatcher.isEffective(r()))
        assertEquals(true, RuleMatcher.isEffective(r(kw = "a")))
    }
}
