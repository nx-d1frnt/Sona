package com.sona.player.automix

import com.sona.player.data.model.Song

/** Smallest changed timeline segment within the bounds touched by a batch of equal-size moves. */
internal fun changedQueueRange(before: List<Song>, after: List<Song>, start: Int, endExclusive: Int): IntRange? {
    require(before.size == after.size)
    var first = start
    var end = endExclusive
    while (first < end && before[first].id == after[first].id) first++
    while (end > first && before[end - 1].id == after[end - 1].id) end--
    return if (first < end) first until end else null
}
