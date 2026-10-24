package com.campfire.smsforwarder.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campfire.smsforwarder.MainViewModel
import com.campfire.smsforwarder.core.Masking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val HeroText = Color.White
private val HeroSoft = Color(0xFFCDEFDD)
private val PausedTop = Color(0xFF2E3A35)
private val PausedBottom = Color(0xFF45524B)
private val PausedSoft = Color(0xFFE1E9E4)

@Composable
fun HomeScreen(vm: MainViewModel, perm: PermissionState, a: PermissionActions, onGoChannels: () -> Unit) {
    val s by vm.settings.collectAsState()
    val logs by vm.logs.collectAsState()

    val readyChannels = s.channels.filter { it.enabled && it.url.isNotBlank() }
    val running = perm.sms && readyChannels.isNotEmpty()

    // 今天 0 点起、不含「测试」的记录
    val dayStart = remember(logs.firstOrNull()?.id) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val real = logs.filter { it.source != "测试" }
    val today = real.filter { it.time >= dayStart }
    val okToday = today.count { it.status == "成功" }
    val failToday = today.count { it.status == "失败" }
    val pendingToday = today.count { it.status == "排队中" || it.status == "重试中" }
    val last = real.firstOrNull()

    val permDone = listOf(perm.sms, perm.notificationAccess, perm.batteryIgnored, perm.postNotifications).count { it }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ——— 主面板：现在是否在转发、今天转了多少条、最近一条去了哪 ———
        val soft = if (running) HeroSoft else PausedSoft
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        if (running) listOf(DeepDark, Deep) else listOf(PausedTop, PausedBottom),
                    ),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "正在转发" else "已暂停",
                    color = HeroText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text("短信通知转发", color = soft, style = MaterialTheme.typography.labelLarge)
            }

            Column {
                Text("今天已转发", color = soft, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$okToday", color = HeroText, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold)
                    Text("  条", color = soft, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            // 最近一条
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("最近一条", color = soft, style = MaterialTheme.typography.labelMedium)
                if (last == null) {
                    Text("还没有转发记录，收到短信后会显示在这里。", color = HeroText, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(last.time), color = HeroText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = soft, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            last.channel, color = HeroText, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(last.status, color = soft, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        Masking.maskCodes(last.preview), color = soft, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 一行事实
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact("通道", "${readyChannels.size} / ${s.channels.size}", soft, Modifier.weight(1f))
                Fact("失败", "$failToday", soft, Modifier.weight(1f))
                Fact("待发送", "$pendingToday", soft, Modifier.weight(1f))
                Fact("短信规则", if (s.rules.none { it.enabled }) "全部" else "${s.rules.count { it.enabled }} 条", soft, Modifier.weight(1f))
            }

            if (!running) {
                Button(
                    onClick = { if (!perm.sms) a.requestSms() else onGoChannels() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = DeepDark),
                ) { Text(if (!perm.sms) "授权接收短信" else "去添加通道", fontWeight = FontWeight.SemiBold) }
                if (!perm.sms) {
                    Text("没有短信权限，收不到任何短信。", color = soft, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("还没有启用的通道，收到的消息无处可发。", color = soft, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ——— 运行条件 ———
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("运行条件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("已就绪 $permDone / 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
            Column {
                PermRow("接收短信", "转发短信必需", perm.sms, "授权", required = true) { a.requestSms() }
                if (!perm.sms) {
                    OutlinedButton(
                        onClick = a.openAppDetails,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
                    ) { Text("弹窗没有出现？到应用详情页手动开启") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermRow("通知使用权", "转发应用通知时需要，只转发你勾选的应用", perm.notificationAccess, "去开启") { a.openNotificationAccess() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermRow("忽略电池优化", "减少被系统清理后台", perm.batteryIgnored, "去设置") { a.openBattery() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermRow("通知权限", "显示常驻通知（安卓 13 及以上）", perm.postNotifications, "授权") { a.requestPostNotifications() }
            }
        }

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("前台服务常驻", fontWeight = FontWeight.SemiBold)
                    Text(
                        "开启后状态栏有一条常驻通知，开机后自动恢复。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.keepAlive, onCheckedChange = { vm.setKeepAlive(it) })
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "短信常含验证码等敏感信息。只把它转发到你自己可控的群或服务器，不要转发到有陌生人的群。",
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(t))

@Composable
private fun StatusDot(running: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (running) Box(Modifier.size(22.dp).alpha(a * 0.5f).clip(CircleShape).background(Brand))
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (running) Color(0xFF7BF0B6) else Color(0xFFF2B35A)))
    }
}

@Composable
private fun Fact(label: String, value: String, soft: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = HeroText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = soft, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PermRow(title: String, hint: String, ok: Boolean, action: String, required: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Default.CheckCircle else if (required) Icons.Default.Warning else Icons.Default.Info,
            contentDescription = if (ok) "已开启" else "未开启",
            tint = if (ok) okColor() else if (required) MaterialTheme.colorScheme.error else warnColor(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        } else {
            Text("已开启", color = okColor(), style = MaterialTheme.typography.labelLarge)
        }
    }
}
