package com.campfire.smsforwarder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.campfire.smsforwarder.MainViewModel

class PermissionActions(
    val requestSms: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val openBattery: () -> Unit,
    val requestPostNotifications: () -> Unit,
    val openAppDetails: () -> Unit,
)

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: MainViewModel, perm: PermissionState, actions: PermissionActions) {
    val tabs = listOf(
        Tab("首页", Icons.Default.Home),
        Tab("通道", Icons.Default.Send),
        Tab("规则", Icons.AutoMirrored.Filled.List),
        Tab("日志", Icons.Default.Notifications),
    )
    var index by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = index == i,
                        onClick = { index = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (index) {
                0 -> HomeScreen(vm, perm, actions) { index = 1 }
                1 -> ChannelsScreen(vm)
                2 -> RulesScreen(vm)
                else -> LogsScreen(vm)
            }
        }
    }
}
