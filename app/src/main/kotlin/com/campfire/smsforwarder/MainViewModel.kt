package com.campfire.smsforwarder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.ForwardMessage
import com.campfire.smsforwarder.core.Rule
import com.campfire.smsforwarder.core.Source
import com.campfire.smsforwarder.data.AppSettings
import com.campfire.smsforwarder.data.LogDb
import com.campfire.smsforwarder.data.LogEntity
import com.campfire.smsforwarder.data.SettingsStore
import com.campfire.smsforwarder.service.KeepAliveService
import com.campfire.smsforwarder.work.Forwarder
import com.campfire.smsforwarder.work.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val dao = LogDb.get(app).dao()

    val settings: StateFlow<AppSettings> =
        store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val logs: StateFlow<List<LogEntity>> =
        dao.recent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 测试发送的结果提示（通道 id -> 文案）。 */
    val testResult = MutableStateFlow<Map<String, String>>(emptyMap())

    fun saveChannel(ch: Channel) = viewModelScope.launch {
        val list = settings.value.channels
        store.saveChannels(if (list.any { it.id == ch.id }) list.map { if (it.id == ch.id) ch else it } else list + ch)
    }

    fun deleteChannel(id: String) = viewModelScope.launch {
        store.saveChannels(settings.value.channels.filterNot { it.id == id })
    }

    fun saveRule(r: Rule) = viewModelScope.launch {
        val list = settings.value.rules
        store.saveRules(if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r)
    }

    fun deleteRule(id: String) = viewModelScope.launch {
        store.saveRules(settings.value.rules.filterNot { it.id == id })
    }

    fun setNotifyApps(apps: Set<String>) = viewModelScope.launch { store.saveNotifyApps(apps) }

    fun setKeepAlive(on: Boolean) = viewModelScope.launch {
        store.setKeepAlive(on)
        val ctx = getApplication<Application>()
        if (on) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
    }

    fun clearLogs() = viewModelScope.launch { dao.clear() }

    /** 测试发送：立即发一次（不重试），结果写入日志，来源标为「测试」。 */
    fun testSend(ch: Channel) = viewModelScope.launch {
        testResult.value = testResult.value + (ch.id to "发送中…")
        val now = System.currentTimeMillis()
        val msg = ForwardMessage("10086", "这是一条测试消息，验证码 123456 会在日志里打码。", now, Source.SMS)
        val r = withContext(Dispatchers.IO) { Sender.send(ch, msg) }
        val text = if (r.ok) "测试成功" else "测试失败：${r.detail}"
        testResult.value = testResult.value + (ch.id to text)
        val row = Forwarder.logRow(msg, ch.name, if (r.ok) "成功" else "失败", r.detail).copy(source = "测试")
        dao.insert(row)
        dao.trim()
    }
}
