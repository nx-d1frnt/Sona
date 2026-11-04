package com.sona.player.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sona.player.data.model.Playlist

class UserLibraryDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "sona_user_library.db"
        private const val DATABASE_VERSION = 2

        // Favorites table
        private const val TABLE_FAVORITES = "favorites"
        private const val COL_FAV_SONG_ID = "song_id"
        private const val COL_FAV_ADDED_AT = "added_at"

        // Playlists table
        private const val TABLE_PLAYLISTS = "playlists"
        private const val COL_PLAYLIST_ID = "id"
        private const val COL_PLAYLIST_NAME = "name"
        private const val COL_PLAYLIST_CREATED_AT = "created_at"
        private const val COL_PLAYLIST_SMART_TYPE = "smart_type"
        private const val COL_PLAYLIST_SEED_SONG_ID = "seed_song_id"

        // Playlist tracks
        private const val TABLE_PLAYLIST_TRACKS = "playlist_tracks"
        private const val COL_PT_PLAYLIST_ID = "playlist_id"
        private const val COL_PT_SONG_ID = "song_id"
        private const val COL_PT_POSITION = "position"
        private const val COL_PT_ADDED_AT = "added_at"

        // Play stats table
        private const val TABLE_PLAY_STATS = "play_stats"
        private const val COL_STAT_SONG_ID = "song_id"
        private const val COL_STAT_PLAY_COUNT = "play_count"
        private const val COL_STAT_LAST_PLAYED_AT = "last_played_at"

        @Volatile
        private var instance: UserLibraryDatabaseHelper? = null

        fun getInstance(context: Context): UserLibraryDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: UserLibraryDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FAVORITES (
                $COL_FAV_SONG_ID INTEGER PRIMARY KEY,
                $COL_FAV_ADDED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                $COL_PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PLAYLIST_NAME TEXT NOT NULL,
                $COL_PLAYLIST_CREATED_AT INTEGER NOT NULL,
                $COL_PLAYLIST_SMART_TYPE TEXT DEFAULT NULL,
                $COL_PLAYLIST_SEED_SONG_ID INTEGER DEFAULT NULL
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_TRACKS (
                $COL_PT_PLAYLIST_ID INTEGER NOT NULL,
                $COL_PT_SONG_ID INTEGER NOT NULL,
                $COL_PT_POSITION INTEGER NOT NULL,
                $COL_PT_ADDED_AT INTEGER NOT NULL,
                PRIMARY KEY ($COL_PT_PLAYLIST_ID, $COL_PT_SONG_ID),
                FOREIGN KEY ($COL_PT_PLAYLIST_ID) REFERENCES $TABLE_PLAYLISTS($COL_PLAYLIST_ID) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAY_STATS (
                $COL_STAT_SONG_ID INTEGER PRIMARY KEY,
                $COL_STAT_PLAY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_STAT_LAST_PLAYED_AT INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_PLAYLIST_SMART_TYPE TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_PLAYLIST_SEED_SONG_ID INTEGER DEFAULT NULL")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // --- Favorites ---

    fun getFavoriteSongIds(): Set<Long> {
        val result = mutableSetOf<Long>()
        val db = readableDatabase
        db.query(
            TABLE_FAVORITES,
            arrayOf(COL_FAV_SONG_ID),
            null,
            null,
            null,
            null,
            "$COL_FAV_ADDED_AT DESC"
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_FAV_SONG_ID)
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(idCol))
            }
        }
        return result
    }

    fun isFavorite(songId: Long): Boolean {
        val db = readableDatabase
        db.query(
            TABLE_FAVORITES,
            arrayOf(COL_FAV_SONG_ID),
            "$COL_FAV_SONG_ID = ?",
            arrayOf(songId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun setFavorite(songId: Long, isFavorite: Boolean) {
        val db = writableDatabase
        if (isFavorite) {
            val values = ContentValues().apply {
                put(COL_FAV_SONG_ID, songId)
                put(COL_FAV_ADDED_AT, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_FAVORITES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete(TABLE_FAVORITES, "$COL_FAV_SONG_ID = ?", arrayOf(songId.toString()))
        }
    }

    // --- Playlists ---

    fun getAllPlaylists(): List<Playlist> {
        val playlists = mutableListOf<Playlist>()
        val db = readableDatabase
        val query = """
            SELECT p.$COL_PLAYLIST_ID, p.$COL_PLAYLIST_NAME, p.$COL_PLAYLIST_CREATED_AT,
                   p.$COL_PLAYLIST_SMART_TYPE, p.$COL_PLAYLIST_SEED_SONG_ID,
                   COUNT(pt.$COL_PT_SONG_ID) AS track_count
            FROM $TABLE_PLAYLISTS p
            LEFT JOIN $TABLE_PLAYLIST_TRACKS pt ON p.$COL_PLAYLIST_ID = pt.$COL_PT_PLAYLIST_ID
            GROUP BY p.$COL_PLAYLIST_ID
            ORDER BY p.$COL_PLAYLIST_NAME COLLATE NOCASE ASC
        """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_PLAYLIST_ID)
            val nameCol = cursor.getColumnIndexOrThrow(COL_PLAYLIST_NAME)
            val createdCol = cursor.getColumnIndexOrThrow(COL_PLAYLIST_CREATED_AT)
            val smartTypeCol = cursor.getColumnIndexOrThrow(COL_PLAYLIST_SMART_TYPE)
            val seedSongIdCol = cursor.getColumnIndexOrThrow(COL_PLAYLIST_SEED_SONG_ID)
            val countCol = cursor.getColumnIndexOrThrow("track_count")

            while (cursor.moveToNext()) {
                val seedSongId = if (!cursor.isNull(seedSongIdCol)) cursor.getLong(seedSongIdCol) else null
                playlists.add(
                    Playlist(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol),
                        trackCount = cursor.getInt(countCol),
                        createdAt = cursor.getLong(createdCol),
                        smartType = cursor.getString(smartTypeCol),
                        seedSongId = seedSongId
                    )
                )
            }
        }
        return playlists
    }

    fun createPlaylist(name: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PLAYLIST_NAME, name.trim())
            put(COL_PLAYLIST_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_PLAYLISTS, null, values)
    }

    fun createSmartPlaylist(name: String, smartType: String, seedSongId: Long? = null): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PLAYLIST_NAME, name.trim())
            put(COL_PLAYLIST_CREATED_AT, System.currentTimeMillis())
            put(COL_PLAYLIST_SMART_TYPE, smartType)
            if (seedSongId != null) {
                put(COL_PLAYLIST_SEED_SONG_ID, seedSongId)
            } else {
                putNull(COL_PLAYLIST_SEED_SONG_ID)
            }
        }
        return db.insert(TABLE_PLAYLISTS, null, values)
    }

    fun replacePlaylistTracks(playlistId: Long, songIds: List<Long>): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_PLAYLIST_TRACKS, "$COL_PT_PLAYLIST_ID = ?", arrayOf(playlistId.toString()))
            val now = System.currentTimeMillis()
            songIds.forEachIndexed { index, songId ->
                val values = ContentValues().apply {
                    put(COL_PT_PLAYLIST_ID, playlistId)
                    put(COL_PT_SONG_ID, songId)
                    put(COL_PT_POSITION, index)
                    put(COL_PT_ADDED_AT, now)
                }
                db.insertWithOnConflict(TABLE_PLAYLIST_TRACKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PLAYLIST_NAME, newName.trim())
        }
        return db.update(TABLE_PLAYLISTS, values, "$COL_PLAYLIST_ID = ?", arrayOf(playlistId.toString())) > 0
    }

    fun deletePlaylist(playlistId: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_PLAYLISTS, "$COL_PLAYLIST_ID = ?", arrayOf(playlistId.toString())) > 0
    }

    fun getPlaylistTrackIds(playlistId: Long): List<Long> {
        val trackIds = mutableListOf<Long>()
        val db = readableDatabase
        db.query(
            TABLE_PLAYLIST_TRACKS,
            arrayOf(COL_PT_SONG_ID),
            "$COL_PT_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "$COL_PT_POSITION ASC"
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_PT_SONG_ID)
            while (cursor.moveToNext()) {
                trackIds.add(cursor.getLong(idCol))
            }
        }
        return trackIds
    }

    fun addTrackToPlaylist(playlistId: Long, songId: Long): Boolean {
        val db = writableDatabase
        // Find max position
        var nextPos = 0
        db.query(
            TABLE_PLAYLIST_TRACKS,
            arrayOf("MAX($COL_PT_POSITION)"),
            "$COL_PT_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                nextPos = cursor.getInt(0) + 1
            }
        }

        val values = ContentValues().apply {
            put(COL_PT_PLAYLIST_ID, playlistId)
            put(COL_PT_SONG_ID, songId)
            put(COL_PT_POSITION, nextPos)
            put(COL_PT_ADDED_AT, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(
            TABLE_PLAYLIST_TRACKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun removeTrackFromPlaylist(playlistId: Long, songId: Long): Boolean {
        val db = writableDatabase
        return db.delete(
            TABLE_PLAYLIST_TRACKS,
            "$COL_PT_PLAYLIST_ID = ? AND $COL_PT_SONG_ID = ?",
            arrayOf(playlistId.toString(), songId.toString())
        ) > 0
    }

    // --- Play Stats (Play count & Recently played) ---

    fun recordPlay(songId: Long) {
        val db = writableDatabase
        val query = """
            INSERT INTO $TABLE_PLAY_STATS ($COL_STAT_SONG_ID, $COL_STAT_PLAY_COUNT, $COL_STAT_LAST_PLAYED_AT)
            VALUES (?, 1, ?)
            ON CONFLICT($COL_STAT_SONG_ID) DO UPDATE SET
                $COL_STAT_PLAY_COUNT = $COL_STAT_PLAY_COUNT + 1,
                $COL_STAT_LAST_PLAYED_AT = ?
        """.trimIndent()
        val now = System.currentTimeMillis()
        db.execSQL(query, arrayOf(songId, now, now))
    }

    fun getTopPlayedSongIds(limit: Int = 50): List<Long> {
        val result = mutableListOf<Long>()
        val db = readableDatabase
        db.query(
            TABLE_PLAY_STATS,
            arrayOf(COL_STAT_SONG_ID),
            "$COL_STAT_PLAY_COUNT > 0",
            null,
            null,
            null,
            "$COL_STAT_PLAY_COUNT DESC, $COL_STAT_LAST_PLAYED_AT DESC",
            limit.toString()
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_STAT_SONG_ID)
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(idCol))
            }
        }
        return result
    }

    fun getPlayStats(): Map<Long, Int> {
        val result = mutableMapOf<Long, Int>()
        val db = readableDatabase
        db.query(
            TABLE_PLAY_STATS,
            arrayOf(COL_STAT_SONG_ID, COL_STAT_PLAY_COUNT),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(COL_STAT_SONG_ID)
            val countCol = cursor.getColumnIndexOrThrow(COL_STAT_PLAY_COUNT)
            while (cursor.moveToNext()) {
                result[cursor.getLong(idCol)] = cursor.getInt(countCol)
            }
        }
        return result
    }
}
