package com.campfire.smsforwarder.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.Source
import com.campfire.smsforwarder.data.SettingsStore
import com.campfire.smsforwarder.work.Forwarder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 接收短信广播（SMS_RECEIVED）。长短信的多段按发件人合并。 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext
        val grouped = parts.groupBy { it.originatingAddress.orEmpty() }
        // 用手机收到的时间，不用短信里的发送时间戳（运营商 / 模拟器的时间戳可能带错时区）
        val time = System.currentTimeMillis()
        scope.launch {
            try {
                for ((from, list) in grouped) {
                    val body = list.joinToString("") { it.messageBody.orEmpty() }
                    Forwarder.onMessage(app, ForwardMessage(from, body, time, Source.SMS))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 开机自启：开启了「前台服务常驻」时拉起前台服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                if (SettingsStore(app).get().keepAlive) KeepAliveService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 转发用户勾选的应用的通知。 */
class NotifyListener : NotificationListenerService() {
    private var lastKey = ""
    private var lastAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // 同一条通知被应用反复更新（如进度条）时，短时间内相同内容只转一次
        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 10_000) return
        lastKey = key; lastAt = now
        val app = applicationContext
        scope.launch {
            if (pkg !in SettingsStore(app).get().notifyApps) return@launch
            val msg = ForwardMessage(
                from = title,
                content = text.ifBlank { title },
                receivedAt = sbn.postTime,
                source = Source.NOTIFICATION,
                app = Forwarder.appLabel(app, pkg),
            )
            Forwarder.onMessage(app, msg)
        }
    }
}
