package com.campfire.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campfire.smsforwarder.MainViewModel

@Composable
fun HomeScreen(vm: MainViewModel, perm: PermissionState, a: PermissionActions) {
    val s by vm.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("短信通知转发", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Text(
                "短信常含验证码等敏感信息。只把它转发到你自己可控的群或服务器，不要转发到有陌生人的群。",
                Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text("权限与运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PermRow("接收短信", "转发短信必需", perm.sms, if (perm.sms) null else "授权") { a.requestSms() }
        if (!perm.sms) {
            OutlinedButton(onClick = a.openAppDetails, modifier = Modifier.fillMaxWidth()) {
                Text("弹窗没有出现？到应用详情页手动开启")
            }
        }
        PermRow("通知使用权", "转发应用通知必需（只转发你勾选的应用）", perm.notificationAccess,
            if (perm.notificationAccess) "查看" else "去开启") { a.openNotificationAccess() }
        PermRow("忽略电池优化", "减少被系统杀后台", perm.batteryIgnored,
            if (perm.batteryIgnored) null else "去设置") { a.openBattery() }
        PermRow("通知权限", "显示常驻通知（安卓 13 及以上）", perm.postNotifications,
            if (perm.postNotifications) null else "授权") { a.requestPostNotifications() }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("前台服务常驻", fontWeight = FontWeight.SemiBold)
                    Text("开启后状态栏有一条常驻通知，开机后自动恢复。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.keepAlive, onCheckedChange = { vm.setKeepAlive(it) })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前配置", fontWeight = FontWeight.SemiBold)
                Text("已启用通道：${s.channels.count { it.enabled }} / ${s.channels.size}", style = MaterialTheme.typography.bodyMedium)
                Text("短信规则：${if (s.rules.none { it.enabled }) "无（转发全部短信）" else "${s.rules.count { it.enabled }} 条"}", style = MaterialTheme.typography.bodyMedium)
                Text("转发通知的应用：${s.notifyApps.size} 个", style = MaterialTheme.typography.bodyMedium)
                if (s.channels.none { it.enabled }) {
                    Text("还没有启用的通道，请到「通道」页添加。", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, hint: String, ok: Boolean, action: String?, onClick: () -> Unit) {
    Card {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "  " + if (ok) "已开启" else "未开启",
                        color = if (ok) OkGreen else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            if (action != null) {
                if (ok) OutlinedButton(onClick = onClick) { Text(action) } else Button(onClick = onClick) { Text(action) }
            }
        }
    }
}
