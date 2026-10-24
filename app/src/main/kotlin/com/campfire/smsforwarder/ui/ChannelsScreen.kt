package com.campfire.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.campfire.smsforwarder.MainViewModel
import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.ChannelType
import com.campfire.smsforwarder.core.Masking
import java.util.UUID

@Composable
fun ChannelsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val results by vm.testResult.collectAsState()
    // editingId：null 表示在列表页；"new" 表示新建；其余为被编辑通道的 id
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Channel?>(null) }

    val editing = editingId
    if (editing != null) {
        ChannelEditor(
            initial = s.channels.firstOrNull { it.id == editing },
            onCancel = { editingId = null },
            onSave = { vm.saveChannel(it); editingId = null },
            onDelete = { id -> vm.deleteChannel(id); editingId = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("转发通道", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(onClick = { editingId = "new" }) { Text("添加通道") }
        }
        if (s.channels.isEmpty()) {
            Text("还没有通道。添加企业微信、钉钉、飞书机器人或自定义 Webhook 后，才会转发。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.channels, key = { it.id }) { ch ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ch.name, fontWeight = FontWeight.SemiBold)
                                Text(ch.type.label, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = ch.enabled, onCheckedChange = { vm.saveChannel(ch.copy(enabled = it)) })
                        }
                        Text("地址：${Masking.maskSecret(ch.url)}", style = MaterialTheme.typography.bodySmall)
                        if (ch.secret.isNotBlank()) {
                            Text("密钥：${Masking.maskSecret(ch.secret, 2, 2)}", style = MaterialTheme.typography.bodySmall)
                        }
                        val r = results[ch.id]
                        if (r != null) {
                            Text(r, color = if (r.startsWith("测试成功")) OkGreen else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.testSend(ch) }) { Text("测试发送") }
                            OutlinedButton(onClick = { editingId = ch.id }) { Text("编辑") }
                            TextButton(onClick = { deleting = ch }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除通道") },
            text = { Text("确定删除「${ch.name}」？") },
            confirmButton = { TextButton(onClick = { vm.deleteChannel(ch.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChannelEditor(initial: Channel?, onCancel: () -> Unit, onSave: (Channel) -> Unit, onDelete: (String) -> Unit) {
    var type by rememberSaveable { mutableStateOf(initial?.type ?: ChannelType.WECOM) }
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    // 已有通道的地址和密钥不回显明文：留空表示保持不变
    var url by rememberSaveable { mutableStateOf("") }
    var secret by rememberSaveable { mutableStateOf("") }
    var clearSecret by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isEdit = initial != null
    val hasSecret = type == ChannelType.DINGTALK || type == ChannelType.FEISHU

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (isEdit) "编辑通道" else "添加通道", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("通道类型")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChannelType.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        FilterChip(selected = type == t, onClick = { if (!isEdit) type = t }, enabled = !isEdit || type == t,
                            label = { Text(t.label) })
                    }
                }
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（自己看的）") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Webhook 地址") },
            placeholder = { Text(urlHint(type)) },
            supportingText = { if (isEdit) Text("当前：${Masking.maskSecret(initial!!.url)}（留空表示不修改）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            minLines = 2,
        )
        if (hasSecret) {
            OutlinedTextField(
                value = secret, onValueChange = { secret = it; clearSecret = false }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (type == ChannelType.DINGTALK) "加签密钥（SEC 开头，可选）" else "签名校验密钥（可选）") },
                supportingText = {
                    Text(if (isEdit && initial!!.secret.isNotBlank()) "当前：${Masking.maskSecret(initial.secret, 2, 2)}（留空表示不修改）" else "未开启签名则不用填")
                },
                singleLine = true,
            )
            if (isEdit && initial!!.secret.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = clearSecret, onCheckedChange = { clearSecret = it })
                    Text("  清除已保存的密钥")
                }
            }
        }
        Text(typeHelp(type), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newUrl = url.trim().ifEmpty { initial?.url.orEmpty() }
                when {
                    name.isBlank() -> error = "请填写名称"
                    !(newUrl.startsWith("https://") || newUrl.startsWith("http://")) -> error = "Webhook 地址需以 http:// 或 https:// 开头"
                    else -> {
                        val newSecret = when {
                            !hasSecret || clearSecret -> ""
                            secret.isNotBlank() -> secret.trim()
                            else -> initial?.secret.orEmpty()
                        }
                        onSave(
                            Channel(
                                id = initial?.id ?: UUID.randomUUID().toString(), type = type, name = name.trim(),
                                enabled = initial?.enabled ?: true, url = newUrl, secret = newSecret,
                            ),
                        )
                    }
                }
            }) { Text("保存") }
            OutlinedButton(onClick = onCancel) { Text("取消") }
            if (isEdit) TextButton(onClick = { onDelete(initial!!.id) }) { Text("删除") }
        }
    }
}

private fun urlHint(t: ChannelType) = when (t) {
    ChannelType.WECOM -> "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…"
    ChannelType.DINGTALK -> "https://oapi.dingtalk.com/robot/send?access_token=…"
    ChannelType.FEISHU -> "https://open.feishu.cn/open-apis/bot/v2/hook/…"
    ChannelType.CUSTOM -> "https://你的服务器/接收地址"
}

private fun typeHelp(t: ChannelType) = when (t) {
    ChannelType.WECOM -> "在企业微信群里添加群机器人，复制它的 Webhook 地址。"
    ChannelType.DINGTALK -> "安全设置建议选「加签」并填入 SEC 开头的密钥；若只能选「自定义关键词」，请同时添加「短信」「通知」两个关键词（短信消息含「短信」，通知消息含「通知」）。"
    ChannelType.FEISHU -> "安全设置若勾选「签名校验」，请填入密钥；若选自定义关键词，请同时添加「短信」「通知」两个关键词。"
    ChannelType.CUSTOM -> "以 POST 发送 JSON：from、content、receivedAt、source（sms 或 notification）、app。"
}
