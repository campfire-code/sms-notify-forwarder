package com.campfire.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campfire.smsforwarder.MainViewModel
import com.campfire.smsforwarder.core.Masking
import com.campfire.smsforwarder.core.RequestBuilder

@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    val ok = logs.count { it.status == "成功" }
    val failed = logs.count { it.status == "失败" }
    val going = logs.count { it.status == "排队中" || it.status == "重试中" }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("转发日志", "最近 200 条，内容里的 4～8 位连续数字（验证码）已打码") {
            if (logs.isNotEmpty()) TextButton(onClick = { vm.clearLogs() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空") }
        }
        if (logs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tally("成功", ok, okColor(), Modifier.weight(1f))
                Tally("失败", failed, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                Tally("进行中", going, warnColor(), Modifier.weight(1f))
            }
        }
        if (logs.isEmpty()) {
            EmptyHint(Icons.Default.Notifications, "还没有转发记录", "收到短信或勾选应用的通知后，每次转发的结果都会记在这里。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs, key = { it.id }) { l ->
                val c = statusColor(l.status)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        Box(Modifier.width(5.dp).fillMaxHeight().background(c))
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("${l.source} → ${l.channel}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(l.status, fontWeight = FontWeight.Bold, color = c, style = MaterialTheme.typography.labelLarge)
                            }
                            // 展示时再打码一次，防止旧数据或手工写入的内容漏网
                            Text(Masking.maskCodes(l.preview), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            if (l.detail.isNotBlank()) {
                                Text(l.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                RequestBuilder.formatTime(l.time), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tally(label: String, n: Int, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("$n", color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun statusColor(s: String): Color = when (s) {
    "成功" -> okColor()
    "失败" -> MaterialTheme.colorScheme.error
    else -> warnColor()
}
