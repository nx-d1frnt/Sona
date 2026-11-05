package com.sona.player.analysis

import android.content.Context
import com.sona.player.data.model.Song
import com.sona.player.data.repository.AudioRepository
import com.sona.player.playback.AutomixPlaybackEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

class LibraryAnalysisRunner(
    private val context: Context,
    private val force: Boolean,
    private val onProgress: (state: LibraryAnalysisState) -> Unit,
    private val onPublishNotification: (() -> Unit)? = null,
    private val onAcquireWakeLock: (() -> Unit)? = null,
    private val onReleaseWakeLock: (() -> Unit)? = null
) {
    suspend fun execute() {
        val engine = AutomixPlaybackEngine.getInstance(context)
        val songs = AudioRepository(context).getSongs()
        val totalCount = songs.size
        val initialCached = if (force) 0 else songs.count { !engine.needsLibraryAnalysis(it.id) }
        val completedCount = AtomicInteger(initialCached)
        val cachedCount = AtomicInteger(initialCached)
        val failedCount = AtomicInteger(0)

        val pendingSongs = if (force) songs else songs.filter { engine.needsLibraryAnalysis(it.id) }
        val etaEstimator = AnalysisEtaEstimator(totalPending = pendingSongs.size)

        onProgress(
            LibraryAnalysisState(
                running = true,
                total = totalCount,
                completed = initialCached,
                cached = initialCached
            )
        )
        onPublishNotification?.invoke()

        if (pendingSongs.isEmpty()) {
            engine.notifyLibraryAnalysisProgress(forceImmediate = true)
            return
        }

        val workerCount = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
        val channel = Channel<Song>(Channel.BUFFERED)

        coroutineScope {
            val producer = launch(Dispatchers.Default) {
                for (song in pendingSongs) {
                    channel.send(song)
                }
                channel.close()
            }

            val workers = (0 until workerCount).map {
                launch(Dispatchers.Default) {
                    for (song in channel) {
                        ensureActive()
                        while (engine.playerState.value.isPlaying || engine.playerState.value.automix.isTransitioning) {
                            onReleaseWakeLock?.invoke()
                            etaEstimator.pause()
                            onProgress(
                                LibraryAnalysisService.state.value.copy(
                                    waitingForPlayback = true
                                )
                            )
                            onPublishNotification?.invoke()
                            delay(1000)
                        }
                        etaEstimator.resume()
                        onProgress(
                            LibraryAnalysisService.state.value.copy(
                                waitingForPlayback = false,
                                title = song.title
                            )
                        )
                        onAcquireWakeLock?.invoke()
                        onPublishNotification?.invoke()

                        try {
                            engine.analyzeLibrarySong(song, force)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failedCount.incrementAndGet()
                        }

                        val currentCompleted = completedCount.incrementAndGet()
                        val newlyCompleted = currentCompleted - initialCached
                        val currentEta = etaEstimator.updateProgress(newlyCompleted)

                        onProgress(
                            LibraryAnalysisService.state.value.copy(
                                completed = currentCompleted,
                                cached = cachedCount.get(),
                                failed = failedCount.get(),
                                etaMillis = currentEta
                            )
                        )
                        onPublishNotification?.invoke()
                    }
                }
            }

            producer.join()
            workers.joinAll()
        }

        engine.notifyLibraryAnalysisProgress(forceImmediate = true)
        engine.flushPendingAnalysisWrites()
    }
}
