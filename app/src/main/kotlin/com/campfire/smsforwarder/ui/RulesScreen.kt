package com.campfire.smsforwarder.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campfire.smsforwarder.MainViewModel
import com.campfire.smsforwarder.core.Rule
import com.campfire.smsforwarder.core.RuleMatcher
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RulesScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    var showApps by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rule?>(null) }

    if (showApps) {
        AppPicker(selected = s.notifyApps, onChange = { vm.setNotifyApps(it) }, onBack = { showApps = false })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("转发规则", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("转发通知的应用", fontWeight = FontWeight.SemiBold)
                    Text("已勾选 ${s.notifyApps.size} 个。需先开启通知使用权。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { showApps = true }) { Text("选择应用") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("短信规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "没有规则时转发全部短信；有规则时命中任意一条才转发。一条规则里填了几项，就要同时满足几项。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { editing = Rule(UUID.randomUUID().toString()) }) { Text("添加") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.rules, key = { it.id }) { r ->
                Card {
                    Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (r.senderContains.isNotBlank()) Text("发件号码包含：${r.senderContains}")
                            if (r.keywordContains.isNotBlank()) Text("内容包含：${r.keywordContains}")
                            if (r.regex.isNotBlank()) Text("正则：${r.regex}")
                            if (!RuleMatcher.isEffective(r)) Text("（空规则，不生效）", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editing = r }) { Text("编辑") }
                                TextButton(onClick = { vm.deleteRule(r.id) }) { Text("删除") }
                            }
                        }
                        Switch(checked = r.enabled, onCheckedChange = { vm.saveRule(r.copy(enabled = it)) })
                    }
                }
            }
        }
    }

    editing?.let { r ->
        RuleDialog(r, onDismiss = { editing = null }, onSave = { vm.saveRule(it); editing = null })
    }
}

@Composable
private fun RuleDialog(rule: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var sender by remember { mutableStateOf(rule.senderContains) }
    var keyword by remember { mutableStateOf(rule.keywordContains) }
    var regex by remember { mutableStateOf(rule.regex) }
    val regexOk = RuleMatcher.isValidRegex(regex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("短信规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("发件号码包含") }, singleLine = true)
                OutlinedTextField(keyword, { keyword = it }, label = { Text("内容关键词包含") }, singleLine = true)
                OutlinedTextField(
                    regex, { regex = it }, label = { Text("正则（可选）") }, singleLine = true, isError = !regexOk,
                    supportingText = { if (!regexOk) Text("正则写法有误") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = regexOk, onClick = {
                onSave(rule.copy(senderContains = sender.trim(), keywordContains = keyword.trim(), regex = regex.trim()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class AppItem(val pkg: String, val label: String)

@Composable
private fun AppPicker(selected: Set<String>, onChange: (Set<String>) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选择要转发通知的应用", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("完成") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("搜索应用") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = apps
        if (list == null) Text("正在加载应用列表…")
        else LazyColumn {
            items(list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = a.pkg in selected, onCheckedChange = { on ->
                        onChange(if (on) selected + a.pkg else selected - a.pkg)
                    })
                    Column {
                        Text(a.label)
                        Text(a.pkg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
