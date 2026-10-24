package com.campfire.smsforwarder.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.campfire.smsforwarder.core.Channel
import com.campfire.smsforwarder.core.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val channels: List<Channel> = emptyList(),
    val rules: List<Rule> = emptyList(),
    /** 勾选要转发通知的应用包名。 */
    val notifyApps: Set<String> = emptySet(),
    /** 前台服务常驻开关。 */
    val keepAlive: Boolean = false,
)

/** 配置存储：渠道含 Webhook 地址和密钥，只存在本机应用私有目录。 */
class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val channelSer = ListSerializer(Channel.serializer())
    private val ruleSer = ListSerializer(Rule.serializer())

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun get(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        channels = this[CHANNELS]?.let { runCatching { json.decodeFromString(channelSer, it) }.getOrNull() } ?: emptyList(),
        rules = this[RULES]?.let { runCatching { json.decodeFromString(ruleSer, it) }.getOrNull() } ?: emptyList(),
        notifyApps = this[APPS] ?: emptySet(),
        keepAlive = this[KEEP_ALIVE] ?: false,
    )

    suspend fun saveChannels(list: List<Channel>) {
        context.dataStore.edit { it[CHANNELS] = json.encodeToString(channelSer, list) }
    }

    suspend fun saveRules(list: List<Rule>) {
        context.dataStore.edit { it[RULES] = json.encodeToString(ruleSer, list) }
    }

    suspend fun saveNotifyApps(apps: Set<String>) {
        context.dataStore.edit { it[APPS] = apps }
    }

    suspend fun setKeepAlive(on: Boolean) {
        context.dataStore.edit { it[KEEP_ALIVE] = on }
    }

    private companion object {
        val CHANNELS = stringPreferencesKey("channels")
        val RULES = stringPreferencesKey("rules")
        val APPS = stringSetPreferencesKey("notify_apps")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
    }
}
