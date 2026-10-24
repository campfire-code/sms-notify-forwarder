package com.campfire.smsforwarder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.campfire.smsforwarder.ui.AppRoot
import com.campfire.smsforwarder.ui.AppTheme
import com.campfire.smsforwarder.ui.PermissionActions
import com.campfire.smsforwarder.ui.PermissionState

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            var perm by remember { mutableStateOf(PermissionState.read(ctx)) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) perm = PermissionState.read(ctx)
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                perm = PermissionState.read(ctx)
            }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                perm = PermissionState.read(ctx)
            }
            val actions = PermissionActions(
                requestSms = {
                    // 被永久拒绝后系统不再弹窗，此时引导到应用详情页手动开启
                    smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
                },
                openNotificationAccess = { safeStart(PermissionState.notificationAccessIntent()) },
                openBattery = {
                    if (!safeStart(PermissionState.batteryIntent(ctx))) safeStart(PermissionState.batteryListIntent())
                },
                requestPostNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                openAppDetails = { safeStart(PermissionState.appDetailsIntent(ctx)) },
            )
            AppTheme { AppRoot(vm, perm, actions) }
        }
    }

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
