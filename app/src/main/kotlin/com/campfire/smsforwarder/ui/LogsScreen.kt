package com.campfire.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campfire.smsforwarder.MainViewModel
import com.campfire.smsforwarder.core.Masking
import com.campfire.smsforwarder.core.RequestBuilder

@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("转发日志", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
        }
        Text("最近 200 条。内容中的 4～8 位连续数字（验证码）已打码。", style = MaterialTheme.typography.bodySmall)
        if (logs.isEmpty()) Text("暂无记录")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs, key = { it.id }) { l ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text(RequestBuilder.formatTime(l.time), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Text(l.status, fontWeight = FontWeight.SemiBold, color = statusColor(l.status))
                        }
                        Text("${l.source} → ${l.channel}", style = MaterialTheme.typography.bodyMedium)
                        // 展示时再打码一次，防止旧数据或手工写入的内容漏网
                        Text(Masking.maskCodes(l.preview), style = MaterialTheme.typography.bodySmall)
                        if (l.detail.isNotBlank()) Text(l.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: String): Color = when (s) {
    "成功" -> OkGreen
    "失败" -> MaterialTheme.colorScheme.error
    else -> WarnAmber
}
