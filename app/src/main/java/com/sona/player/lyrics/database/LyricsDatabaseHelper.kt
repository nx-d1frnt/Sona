package com.sona.player.lyrics.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sona.player.lyrics.model.LyricSource
import com.sona.player.lyrics.model.TrackLyrics
import com.sona.player.lyrics.parser.LrcParser

class LyricsDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "sona_lyrics.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NAME = "lyrics_cache"
        private const val COL_SONG_ID = "song_id"
        private const val COL_SOURCE = "source"
        private const val COL_RAW_LRC = "raw_lrc"
        private const val COL_PLAIN_LYRICS = "plain_lyrics"
        private const val COL_IS_SYNCED = "is_synced"
        private const val COL_USER_OFFSET_MS = "user_offset_ms"
        private const val COL_UPDATED_AT = "updated_at"

        @Volatile
        private var instance: LyricsDatabaseHelper? = null

        fun getInstance(context: Context): LyricsDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: LyricsDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_SONG_ID INTEGER PRIMARY KEY,
                $COL_SOURCE TEXT NOT NULL,
                $COL_RAW_LRC TEXT,
                $COL_PLAIN_LYRICS TEXT,
                $COL_IS_SYNCED INTEGER NOT NULL DEFAULT 1,
                $COL_USER_OFFSET_MS INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun getLyrics(songId: Long): TrackLyrics? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_SOURCE, COL_RAW_LRC, COL_PLAIN_LYRICS, COL_IS_SYNCED, COL_USER_OFFSET_MS),
            "$COL_SONG_ID = ?",
            arrayOf(songId.toString()),
            null,
            null,
            null
        )

        return cursor.use { c ->
            if (c.moveToFirst()) {
                val sourceStr = c.getString(0) ?: LyricSource.LOCAL_FILE.name
                val source = runCatching { LyricSource.valueOf(sourceStr) }.getOrDefault(LyricSource.LOCAL_FILE)
                val rawLrc = c.getString(1)
                val plainLyrics = c.getString(2)
                val isSynced = c.getInt(3) == 1
                val userOffsetMs = c.getLong(4)

                if (isSynced && !rawLrc.isNullOrBlank()) {
                    LrcParser.parse(songId, rawLrc, source, userOffsetMs)
                } else {
                    TrackLyrics(
                        songId = songId,
                        lines = emptyList(),
                        plainLyrics = plainLyrics,
                        isSynced = false,
                        userOffsetMs = userOffsetMs,
                        source = source
                    )
                }
            } else {
                null
            }
        }
    }

    fun saveLyrics(songId: Long, source: LyricSource, rawLrc: String?, plainLyrics: String?, isSynced: Boolean, userOffsetMs: Long = 0L) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SONG_ID, songId)
            put(COL_SOURCE, source.name)
            put(COL_RAW_LRC, rawLrc)
            put(COL_PLAIN_LYRICS, plainLyrics)
            put(COL_IS_SYNCED, if (isSynced) 1 else 0)
            put(COL_USER_OFFSET_MS, userOffsetMs)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateOffset(songId: Long, offsetMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_USER_OFFSET_MS, offsetMs)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COL_SONG_ID = ?", arrayOf(songId.toString()))
    }

    fun deleteLyrics(songId: Long) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COL_SONG_ID = ?", arrayOf(songId.toString()))
    }
}
