package com.sona.player.automix

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import com.sona.player.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Opt-in device regression check; build with -PvocalDiagnostics=true. */
class VocalDetectorDiagnostics : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        try {
            runBlocking {
                if (arguments.getString("benchmark") == "true") {
                    report.append(VocalInferenceBenchmark.run(targetContext,
                        arguments.getString("songIds")!!.substringBefore(',').toLong()))
                    return@runBlocking
                }
                val engine = AudioAnalysisEngine(targetContext)
                try {
                    val ids = arguments.getString("songIds").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                    check(ids.isNotEmpty()) { "Pass at least one song ID with -e songIds" }
                    coroutineScope {
                        ids.map { id -> async {
                            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            val song = targetContext.contentResolver.query(uri,
                                arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION), null, null, null)!!.use {
                                check(it.moveToFirst()) { "Missing song $id" }
                                Song(id, it.getString(0), "", "", it.getLong(1), uri)
                            }
                            val before = engine.getCached(id)
                            val started = System.currentTimeMillis()
                            val force = arguments.getString("force") == "true"
                            val after = engine.analyzeSong(song, force = force)
                            check(after.hasCurrentVocalAnalysis) { "Vocal analysis did not finish for $id" }
                            check(!engine.needsAnalysis(id)) { "Updated song still needs analysis" }
                            if (!force && before != null && before.featureAnalysisAttempted) {
                                check(before.bpm == after.bpm && before.beatSections == after.beatSections) {
                                    "Vocal enrichment changed cached rhythm data"
                                }
                            }
                            report.append("${song.title}: vocals=${after.vocalPercentage}% " +
                                "version=${after.vocalAnalysisVersion} sections=${after.vocalSections.size} " +
                                "elapsedMs=${System.currentTimeMillis() - started}\n")
                        }
                        }.awaitAll()
                    }
                    engine.flushPendingWrites()
                    val reloaded = AudioAnalysisEngine(targetContext)
                    for (id in ids) {
                        check(reloaded.getCached(id)?.hasCurrentVocalAnalysis == true) { "Vocal version not persisted" }
                        check(!reloaded.needsAnalysis(id))
                    }
                } finally {
                    engine.releaseVocalDetector()
                }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, report.toString()) })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString(REPORT_KEY_STREAMRESULT, report.toString() + failure.stackTraceToString())
            })
        }
    }
}
