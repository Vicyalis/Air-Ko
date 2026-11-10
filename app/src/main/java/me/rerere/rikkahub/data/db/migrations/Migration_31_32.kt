package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_31_32"

/**
 * conversationentity 增加 `temporary`：
 * 临时对话以前只存在内存里，重启/进程回收后会被当成普通历史；现在落库后由列表查询排除，
 * 退出时仍按原逻辑删除。
 */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 31 to 32 (conversation temporary)")
        DatabaseMigrationTracker.onMigrationStart(31, 32)
        db.beginTransaction()
        try {
            db.execSQL(
                "ALTER TABLE conversationentity ADD COLUMN temporary INTEGER NOT NULL DEFAULT 0"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 31 to 32 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
