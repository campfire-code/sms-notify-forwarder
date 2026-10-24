package com.campfire.smsforwarder.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 日志只存打码后的内容（4～8 位连续数字已替换为 *），数据库里没有原始验证码。 */
@Entity(tableName = "forward_log")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    /** 短信 / 通知 / 测试 */
    val source: String,
    val channel: String,
    /** 排队中 / 重试中 / 成功 / 失败 */
    val status: String,
    val detail: String,
    /** 打码后的内容摘要 */
    val preview: String,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(e: LogEntity): Long

    @Query("UPDATE forward_log SET status = :status, detail = :detail WHERE id = :id")
    suspend fun update(id: Long, status: String, detail: String)

    @Query("SELECT * FROM forward_log ORDER BY id DESC LIMIT 200")
    fun recent(): Flow<List<LogEntity>>

    @Query("DELETE FROM forward_log WHERE id NOT IN (SELECT id FROM forward_log ORDER BY id DESC LIMIT 200)")
    suspend fun trim()

    @Query("DELETE FROM forward_log")
    suspend fun clear()
}

@Database(entities = [LogEntity::class], version = 1, exportSchema = false)
abstract class LogDb : RoomDatabase() {
    abstract fun dao(): LogDao

    companion object {
        @Volatile private var inst: LogDb? = null
        fun get(context: Context): LogDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(context.applicationContext, LogDb::class.java, "forward-log.db")
                .build().also { inst = it }
        }
    }
}
