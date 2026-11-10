package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_32_33"

/**
 * workspaces 增加：
 * - `shared_dir_path` / `shared_dir_mode`：工作区文件区与 Android 共享目录的绑定方式
 * - `allow_all_tools`：工作区级“允许全部命令”
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 32 to 33 (workspace shared dir and allow all)")
        DatabaseMigrationTracker.onMigrationStart(32, 33)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE workspaces ADD COLUMN shared_dir_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workspaces ADD COLUMN shared_dir_mode TEXT NOT NULL DEFAULT 'bind'")
            db.execSQL("ALTER TABLE workspaces ADD COLUMN allow_all_tools INTEGER NOT NULL DEFAULT 0")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 32 to 33 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
