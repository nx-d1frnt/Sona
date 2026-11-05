package com.sona.player.automix

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-performance, crash-resilient local SQLite storage for Automix track analysis.
 * Replaces O(N^2) monolithic JSON rewrites with O(1) single-row transactions.
 */
class AnalysisDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "sona_automix_analysis.db"
        private const val DATABASE_VERSION = 4

        private const val TABLE_NAME = "track_analysis"
        private const val COL_SONG_ID = "song_id"
        private const val COL_BPM = "bpm"
        private const val COL_ENERGY = "energy"
        private const val COL_CUE_IN_MS = "cue_in_ms"
        private const val COL_MIX_OUT_MS = "mix_out_ms"
        private const val COL_MID_MIX_OUT_MS = "mid_mix_out_ms"
        private const val COL_MID_MIX_CONFIDENCE = "mid_mix_confidence"
        private const val COL_DEEP_CUE_IN_MS = "deep_cue_in_ms"
        private const val COL_CROSSFADE_DURATION_MS = "crossfade_duration_ms"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_FEATURE_ANALYSIS_ATTEMPTED = "feature_analysis_attempted"
        private const val COL_LOUDNESS_ANALYZED = "loudness_analyzed"
        private const val COL_BEATS_JSON = "beats_json"
        private const val COL_VOCALS_JSON = "vocals_json"
        private const val COL_VOCAL_VERSION = "vocal_version"
        private const val COL_LOUDNESS_JSON = "loudness_json"
        private const val COL_KEY_INDEX = "key_index"
        private const val COL_KEY_CONFIDENCE = "key_confidence"
        private const val COL_KEY_ANALYZED = "key_analyzed"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_SONG_ID INTEGER PRIMARY KEY,
                $COL_BPM REAL NOT NULL,
                $COL_ENERGY REAL NOT NULL,
                $COL_CUE_IN_MS INTEGER NOT NULL,
                $COL_MIX_OUT_MS INTEGER NOT NULL,
                $COL_MID_MIX_OUT_MS INTEGER NOT NULL,
                $COL_MID_MIX_CONFIDENCE REAL NOT NULL,
                $COL_DEEP_CUE_IN_MS INTEGER NOT NULL,
                $COL_CROSSFADE_DURATION_MS INTEGER NOT NULL,
                $COL_DURATION_MS INTEGER NOT NULL,
                $COL_FEATURE_ANALYSIS_ATTEMPTED INTEGER NOT NULL,
                $COL_LOUDNESS_ANALYZED INTEGER NOT NULL,
                $COL_BEATS_JSON TEXT,
                $COL_VOCALS_JSON TEXT,
                $COL_VOCAL_VERSION INTEGER NOT NULL DEFAULT 0,
                $COL_LOUDNESS_JSON TEXT,
                $COL_KEY_INDEX INTEGER NOT NULL DEFAULT -1,
                $COL_KEY_CONFIDENCE REAL NOT NULL DEFAULT 0,
                $COL_KEY_ANALYZED INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_song_id ON $TABLE_NAME ($COL_SONG_ID)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: musical key. Existing rows get key_analyzed = 0 and are enriched lazily.
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_KEY_INDEX INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_KEY_CONFIDENCE REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_KEY_ANALYZED INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Preserve other analysis while scheduling old YAMNet-only vocal data for enrichment.
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_VOCAL_VERSION INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            // v4: improved key detection (spectral peak-picking and modern Temperley profiles).
            // Re-analyze key for existing rows on their next enrichment pass.
            db.execSQL("UPDATE $TABLE_NAME SET $COL_KEY_ANALYZED = 0, $COL_KEY_INDEX = -1, $COL_KEY_CONFIDENCE = 0")
        }
    }

    fun insertOrUpdate(analysis: TrackAnalysis) {
        val db = writableDatabase
        val values = analysisToContentValues(analysis)
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertAll(analyses: Collection<TrackAnalysis>) {
        if (analyses.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (analysis in analyses) {
                val values = analysisToContentValues(analysis)
                db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAll(): Map<Long, TrackAnalysis> {
        val result = mutableMapOf<Long, TrackAnalysis>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_NAME", null)
        cursor.use {
            val songIdCol = it.getColumnIndexOrThrow(COL_SONG_ID)
            val bpmCol = it.getColumnIndexOrThrow(COL_BPM)
            val energyCol = it.getColumnIndexOrThrow(COL_ENERGY)
            val cueInCol = it.getColumnIndexOrThrow(COL_CUE_IN_MS)
            val mixOutCol = it.getColumnIndexOrThrow(COL_MIX_OUT_MS)
            val midMixOutCol = it.getColumnIndexOrThrow(COL_MID_MIX_OUT_MS)
            val midConfCol = it.getColumnIndexOrThrow(COL_MID_MIX_CONFIDENCE)
            val deepCueCol = it.getColumnIndexOrThrow(COL_DEEP_CUE_IN_MS)
            val crossfadeCol = it.getColumnIndexOrThrow(COL_CROSSFADE_DURATION_MS)
            val durationCol = it.getColumnIndexOrThrow(COL_DURATION_MS)
            val featAttemptedCol = it.getColumnIndexOrThrow(COL_FEATURE_ANALYSIS_ATTEMPTED)
            val loudnessAnalyzedCol = it.getColumnIndexOrThrow(COL_LOUDNESS_ANALYZED)
            val beatsJsonCol = it.getColumnIndexOrThrow(COL_BEATS_JSON)
            val vocalsJsonCol = it.getColumnIndexOrThrow(COL_VOCALS_JSON)
            val loudnessJsonCol = it.getColumnIndexOrThrow(COL_LOUDNESS_JSON)
            val keyIndexCol = it.getColumnIndexOrThrow(COL_KEY_INDEX)
            val keyConfidenceCol = it.getColumnIndexOrThrow(COL_KEY_CONFIDENCE)
            val keyAnalyzedCol = it.getColumnIndexOrThrow(COL_KEY_ANALYZED)

            while (it.moveToNext()) {
                val songId = it.getLong(songIdCol)
                val dur = it.getLong(durationCol).coerceAtLeast(15_000L)
                val rawMid = it.getLong(midMixOutCol)
                val safeMid = rawMid.coerceIn((dur * 0.50f).toLong(), (dur * 0.67f).toLong())
                val analysis = TrackAnalysis(
                    songId = songId,
                    bpm = it.getFloat(bpmCol),
                    energy = it.getFloat(energyCol),
                    cueInMs = it.getLong(cueInCol),
                    mixOutMs = it.getLong(mixOutCol),
                    midMixOutMs = safeMid,
                    midMixConfidence = it.getFloat(midConfCol).coerceIn(0f, 1f),
                    deepCueInMs = it.getLong(deepCueCol),
                    crossfadeDurationMs = it.getLong(crossfadeCol),
                    durationMs = dur,
                    beatSections = parseBeatsJson(it.getString(beatsJsonCol)),
                    vocalSections = parseVocalsJson(it.getString(vocalsJsonCol)),
                    vocalAnalysisVersion = it.getInt(it.getColumnIndexOrThrow(COL_VOCAL_VERSION)),
                    loudnessSections = parseLoudnessJson(it.getString(loudnessJsonCol)),
                    featureAnalysisAttempted = it.getInt(featAttemptedCol) == 1,
                    loudnessAnalyzed = it.getInt(loudnessAnalyzedCol) == 1,
                    keyIndex = it.getInt(keyIndexCol).takeIf { k -> k in 0..23 } ?: -1,
                    keyConfidence = it.getFloat(keyConfidenceCol).takeIf { c -> c.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                    keyAnalyzed = it.getInt(keyAnalyzedCol) == 1
                )
                result[songId] = analysis
            }
        }
        return result
    }

    fun count(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null)
        return cursor.use {
            if (it.moveToNext()) it.getInt(0) else 0
        }
    }

    fun clearAll(): Int {
        return writableDatabase.delete(TABLE_NAME, null, null)
    }

    private fun analysisToContentValues(analysis: TrackAnalysis): ContentValues {
        val safeBpm = if (analysis.bpm.isFinite()) analysis.bpm else 120.0f
        val safeEnergy = if (analysis.energy.isFinite()) analysis.energy else 0.6f
        val beatsJson = JSONArray().apply {
            analysis.beatSections.forEach { section ->
                put(JSONObject().apply {
                    put("start", section.startMs)
                    put("end", section.endMs)
                    put("bpm", section.bpm)
                    put("confidence", section.confidence)
                    put("downbeatPhase", section.downbeatPhase)
                    put("times", JSONArray(section.beatsMs))
                })
            }
        }.toString()

        val vocalsJson = JSONArray().apply {
            analysis.vocalSections.forEach { section ->
                put(JSONObject().apply {
                    put("start", section.startMs)
                    put("end", section.endMs)
                    put("score", section.score)
                })
            }
        }.toString()

        val loudnessJson = JSONArray().apply {
            analysis.loudnessSections.forEach { section ->
                put(JSONObject().apply {
                    put("start", section.startMs)
                    put("end", section.endMs)
                    put("lufs", section.lufs)
                    put("peak", section.samplePeak)
                })
            }
        }.toString()

        return ContentValues().apply {
            put(COL_SONG_ID, analysis.songId)
            put(COL_BPM, safeBpm)
            put(COL_ENERGY, safeEnergy)
            put(COL_CUE_IN_MS, analysis.cueInMs)
            put(COL_MIX_OUT_MS, analysis.mixOutMs)
            put(COL_MID_MIX_OUT_MS, analysis.midMixOutMs)
            put(COL_MID_MIX_CONFIDENCE, analysis.midMixConfidence)
            put(COL_DEEP_CUE_IN_MS, analysis.deepCueInMs)
            put(COL_CROSSFADE_DURATION_MS, analysis.crossfadeDurationMs)
            put(COL_DURATION_MS, analysis.durationMs)
            put(COL_FEATURE_ANALYSIS_ATTEMPTED, if (analysis.featureAnalysisAttempted) 1 else 0)
            put(COL_LOUDNESS_ANALYZED, if (analysis.loudnessAnalyzed) 1 else 0)
            put(COL_BEATS_JSON, beatsJson)
            put(COL_VOCALS_JSON, vocalsJson)
            put(COL_VOCAL_VERSION, analysis.vocalAnalysisVersion)
            put(COL_LOUDNESS_JSON, loudnessJson)
            put(COL_KEY_INDEX, analysis.keyIndex)
            put(COL_KEY_CONFIDENCE, if (analysis.keyConfidence.isFinite()) analysis.keyConfidence else 0f)
            put(COL_KEY_ANALYZED, if (analysis.keyAnalyzed) 1 else 0)
        }
    }

    private fun parseBeatsJson(json: String?): List<BeatSection> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val bpm = obj.getDouble("bpm").toFloat()
                val confidence = obj.getDouble("confidence").toFloat()
                val start = obj.getLong("start")
                val end = obj.getLong("end")
                if (!bpm.isFinite() || bpm <= 0 || !confidence.isFinite() || end <= start) null
                else {
                    val times = obj.optJSONArray("times") ?: JSONArray()
                    val downbeatPhase = obj.optInt("downbeatPhase", 0).coerceIn(0, 3)
                    BeatSection(
                        start, end, bpm, confidence.coerceIn(0f, 1f),
                        (0 until times.length()).map { times.getLong(it) }.filter { it in start..end }.distinct().sorted(),
                        downbeatPhase
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseVocalsJson(json: String?): List<VocalSection> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val score = obj.getDouble("score").toFloat()
                val start = obj.getLong("start")
                val end = obj.getLong("end")
                if (!score.isFinite() || end <= start) null
                else VocalSection(start, end, score.coerceIn(0f, 1f))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLoudnessJson(json: String?): List<LoudnessSection> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val lufs = obj.getDouble("lufs").toFloat()
                val peak = obj.getDouble("peak").toFloat()
                val start = obj.getLong("start")
                val end = obj.getLong("end")
                if (!lufs.isFinite() || !peak.isFinite() || peak <= 0f || end <= start) null
                else LoudnessSection(start, end, lufs, peak)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
