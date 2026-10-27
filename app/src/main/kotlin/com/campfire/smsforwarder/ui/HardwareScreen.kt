package com.campfire.smsforwarder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 完整说明（接线、命令、验收用例）在仓库的 hardware/README.md，这里只讲取舍和步骤概要。 */
private const val HARDWARE_DOC = "https://github.com/campfire-code/sms-notify-forwarder/tree/main/hardware"

@Composable
fun HardwareScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("硬件方案", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "用插着 SIM 卡的硬件收短信",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("什么时候用手机，什么时候用硬件")
        Card {
            Item("用手机", "手机一直在身边、能保持联网，系统不会清理后台。最省事，还能转发 App 通知。")
            Item("用硬件", "手机后台常被系统杀掉而漏转；想用一张专用 SIM 卡收验证码；需要 24 小时无人值守（供电和网络要自己保证）。")
            Item("只能手机", "转发微信、支付宝等 App 的通知。硬件只收短信。")
        }

        SectionTitle("两种硬件")
        Card {
            Item("USB 串口短信接收器 + 电脑", "硬件通过 USB 接电脑，电脑上运行接收器脚本，只读串口、解析短信并推送。需要 Python 3.9 以上。接收器的输出格式因型号而异，未核实，解析不了的会记为解码失败。")
            Item("Air780EPM 4G 模组", "合宙 Air780EPM，刷入固件后自己联网把短信推送出去，不需要电脑。钉钉「加签」、飞书「签名校验」不支持，机器人安全设置请选「自定义关键词」并添加「短信」。")
        }

        SectionTitle("步骤概要")
        Card {
            Item("1. 准备配置", "在硬件目录里复制配置示例，填写你自己的企业微信、钉钉、飞书机器人地址或自建接收地址，写法与本应用的通道、规则一致。")
            Item("2. 接线", "插入可收短信的 SIM 卡、接好天线、USB 供电。模组的引脚、供电和进入下载模式的方法，本工具没有这部分资料，未核实，请看合宙官方硬件文档。")
            Item("3. 运行或刷固件", "USB 接收器：运行接收器脚本，先用测试命令向群里发一条消息。Air780EPM：用合宙 Luatools 把脚本和配置下载进模组，具体操作未核实，见官方说明。")
            Item("4. 验证", "用另一部手机给 SIM 卡发一条短信，群里应在几秒内收到「【短信】来自 …」。")
        }

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                "硬件和手机用的是同一类转发格式。同一张卡不要同时让手机和硬件转发，否则群里会收到两遍。" +
                    "配置文件里的机器人地址等同密码，不要发给别人。",
                modifier = Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { uri.openUri(HARDWARE_DOC) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("查看完整说明（接线、命令、验收用例）") }
        Text(
            "完整说明在项目仓库的 hardware 目录，需要联网打开。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一块圆角卡片，里面放若干 Item。 */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Item(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
