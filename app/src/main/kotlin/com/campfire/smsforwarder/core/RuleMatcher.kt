package com.campfire.smsforwarder.core

object RuleMatcher {
    /** 没有任何有效规则时转发全部；否则命中任一有效规则才转发。 */
    fun shouldForward(rules: List<Rule>, from: String, content: String): Boolean {
        val active = rules.filter { it.enabled && isEffective(it) }
        if (active.isEmpty()) return true
        return active.any { matches(it, from, content) }
    }

    fun isEffective(rule: Rule): Boolean =
        rule.senderContains.isNotBlank() || rule.keywordContains.isNotBlank() || rule.regex.isNotBlank()

    fun matches(rule: Rule, from: String, content: String): Boolean {
        if (rule.senderContains.isNotBlank() && !from.contains(rule.senderContains.trim())) return false
        if (rule.keywordContains.isNotBlank() && !content.contains(rule.keywordContains.trim())) return false
        if (rule.regex.isNotBlank()) {
            val re = try {
                Regex(rule.regex)
            } catch (e: IllegalArgumentException) {
                return false // 正则写错的规则不命中
            }
            if (!re.containsMatchIn(content)) return false
        }
        return true
    }

    /** 校验正则是否合法，界面保存前用。 */
    fun isValidRegex(pattern: String): Boolean =
        pattern.isBlank() || try { Regex(pattern); true } catch (e: IllegalArgumentException) { false }
}
