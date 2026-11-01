package com.sona.player.automix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One decoder and one feature worker; rendezvous bounds lookahead to one prepared window. */
internal object AnalysisPipeline {
    suspend fun <I, O> process(items: List<I>, prepare: suspend (I) -> O, consume: suspend (O) -> Unit) = coroutineScope {
        val channel = Channel<O>(Channel.RENDEZVOUS)
        val decoder = launch(Dispatchers.Default) {
            try {
                for (item in items) channel.send(prepare(item))
            } finally {
                channel.close()
            }
        }
        try {
            withContext(Dispatchers.Default) { for (item in channel) consume(item) }
        } finally {
            channel.cancel()
            decoder.cancel()
        }
    }
}
