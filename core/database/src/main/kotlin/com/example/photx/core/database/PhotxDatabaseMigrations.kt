package com.example.photx.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit SQLite schema migrations for [PhotxDatabase].
 */
public object PhotxDatabaseMigrations {
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN upload_correlation_token TEXT")
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN upload_attempt_uncertain INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN dedup_canonical_id TEXT"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_media_correlation_token " +
                    "ON media_items (upload_correlation_token)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS backup_execution_lease (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "owner_worker_id TEXT, " +
                    "active_media_id TEXT, " +
                    "acquired_at_ms INTEGER NOT NULL, " +
                    "expires_at_ms INTEGER NOT NULL, " +
                    "fencing_token INTEGER NOT NULL DEFAULT 0" +
                    ")"
            )
        }
    }
}
