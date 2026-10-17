package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_30_31"

/**
 * workspaces 增加桌面方案三列:
 * - `desktop`: 方案标识, 默认 'none' (不启用)
 * - `desktop_custom_command`: 自定义方案的启动命令
 * - `desktop_custom_port`: 自定义方案的端口, 0 表示未设置
 *
 * 用裸 ALTER TABLE (而不是 AutoMigration): 这是纯加列, 列默认值就是需求里的字面值,
 * 手写迁移能保证数据库里的 DEFAULT 与 [me.rerere.rikkahub.data.db.AppDatabase] 声明的完全一致。
 */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 30 to 31 (workspace desktop columns)")
        DatabaseMigrationTracker.onMigrationStart(30, 31)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE workspaces ADD COLUMN desktop TEXT NOT NULL DEFAULT 'none'")
            db.execSQL("ALTER TABLE workspaces ADD COLUMN desktop_custom_command TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workspaces ADD COLUMN desktop_custom_port INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 30 to 31 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
