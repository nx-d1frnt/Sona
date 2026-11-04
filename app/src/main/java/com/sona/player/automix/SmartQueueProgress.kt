package com.sona.player.automix

/** Reuse completed links across deck changes, while invalidating changed analysis/mode/entry inputs. */
internal class SmartQueueProgress {
    private data class Link(val from: TrackAnalysis, val to: TrackAnalysis, val mode: AutomixMode,
                            val entryMs: Long, val nextEntryMs: Long)
    private val links = mutableMapOf<String, Link>()

    fun record(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode, entryMs: Long, nextEntryMs: Long) {
        links[SavedTransition.key(from.songId, to.songId)] = Link(from, to, mode, entryMs, nextEntryMs)
    }

    fun nextEntryIfUnchanged(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode, entryMs: Long): Long? {
        val link = links[SavedTransition.key(from.songId, to.songId)] ?: return null
        return link.nextEntryMs.takeIf {
            link.from === from && link.to === to && link.mode == mode && link.entryMs == entryMs
        }
    }

    fun retainPairs(keys: Set<String>) { links.keys.retainAll(keys) }
    fun clear() { links.clear() }
}
