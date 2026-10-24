package com.campfire.smsforwarder.work

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.Masking
import com.campfire.smsforwarder.core.RuleMatcher
import com.campfire.smsforwarder.core.Source
import com.campfire.smsforwarder.data.LogDb
import com.campfire.smsforwarder.data.LogEntity
import com.campfire.smsforwarder.data.SettingsStore
import java.util.concurrent.TimeUnit

/** 转发入口：短信 / 通知到达后调用，为每个启用的通道各排一个带重试的任务。 */
object Forwarder {
    // 首次发送 + 3 次重试
    const val MAX_ATTEMPTS = 4
    private const val MAX_CONTENT = 4000 // WorkManager 输入数据上限约 10KB

    suspend fun onMessage(context: Context, msg: ForwardMessage) {
        val settings = SettingsStore(context).get()
        if (msg.source == Source.SMS && !RuleMatcher.shouldForward(settings.rules, msg.from, msg.content)) return
        val channels = settings.channels.filter { it.enabled && it.url.isNotBlank() }
        if (channels.isEmpty()) return
        val dao = LogDb.get(context).dao()
        for (ch in channels) {
            val logId = dao.insert(logRow(msg, ch.name, "排队中", ""))
            dao.trim()
            val data = Data.Builder()
                .putString(ForwardWorker.K_FROM, msg.from)
                .putString(ForwardWorker.K_CONTENT, msg.content.take(MAX_CONTENT))
                .putLong(ForwardWorker.K_TIME, msg.receivedAt)
                .putString(ForwardWorker.K_SOURCE, msg.source.wire)
                .putString(ForwardWorker.K_APP, msg.app)
                .putString(ForwardWorker.K_CHANNEL, ch.id)
                .putLong(ForwardWorker.K_LOG, logId)
                .build()
            val req = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                // 指数退避：10 秒起（WorkManager 允许的最小值），每次翻倍
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    fun logRow(msg: ForwardMessage, channelName: String, status: String, detail: String) = LogEntity(
        time = System.currentTimeMillis(),
        source = msg.source.label,
        channel = channelName,
        status = status,
        detail = detail,
        preview = Masking.maskCodes(msg.content).take(120),
    )

    fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}

class ForwardWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val d = inputData
        val msg = ForwardMessage(
            from = d.getString(K_FROM).orEmpty(),
            content = d.getString(K_CONTENT).orEmpty(),
            receivedAt = d.getLong(K_TIME, System.currentTimeMillis()),
            source = if (d.getString(K_SOURCE) == Source.NOTIFICATION.wire) Source.NOTIFICATION else Source.SMS,
            app = d.getString(K_APP).orEmpty(),
        )
        val dao = LogDb.get(applicationContext).dao()
        val logId = d.getLong(K_LOG, -1)
        val channel = SettingsStore(applicationContext).get().channels.firstOrNull { it.id == d.getString(K_CHANNEL) }
        if (channel == null) {
            if (logId > 0) dao.update(logId, "失败", "通道已被删除")
            return Result.failure()
        }
        val r = Sender.send(channel, msg)
        val attempt = runAttemptCount + 1
        return when {
            r.ok -> {
                if (logId > 0) dao.update(logId, "成功", if (attempt > 1) "第 $attempt 次成功" else r.detail)
                Result.success()
            }
            attempt < Forwarder.MAX_ATTEMPTS -> {
                if (logId > 0) dao.update(logId, "重试中", "第 $attempt 次失败：${r.detail}")
                Result.retry()
            }
            else -> {
                if (logId > 0) dao.update(logId, "失败", "已重试 ${Forwarder.MAX_ATTEMPTS} 次仍失败：${r.detail}")
                Result.failure()
            }
        }
    }

    companion object {
        const val K_FROM = "from"
        const val K_CONTENT = "content"
        const val K_TIME = "time"
        const val K_SOURCE = "source"
        const val K_APP = "app"
        const val K_CHANNEL = "channel"
        const val K_LOG = "log"
    }
}
