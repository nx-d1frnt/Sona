package com.sona.player.automix

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisPipelineTest {
    @Test fun nextWindowDecodesWhileCurrentWindowIsAnalysedInOrder() = runBlocking {
        withTimeout(5000) {
            val secondPrepared = CompletableDeferred<Unit>()
            val consumed = mutableListOf<Int>()
            AnalysisPipeline.process(listOf(1, 2, 3), prepare = {
                if (it == 2) secondPrepared.complete(Unit)
                it
            }, consume = {
                if (it == 1) secondPrepared.await() // Serial implementation would deadlock.
                consumed += it
            })
            assertEquals(listOf(1, 2, 3), consumed)
        }
    }

    @Test fun cancellationStopsDecoderAndDoesNotRunUnboundedLookahead() = runBlocking {
        val inFirst = CompletableDeferred<Unit>()
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        val job = launch {
            AnalysisPipeline.process((1..100).toList(), prepare = { decoded.incrementAndGet(); it }, consume = {
                inFirst.complete(Unit)
                awaitCancellation()
            })
        }
        inFirst.await()
        job.cancelAndJoin()
        assertTrue(decoded.get() <= 2)
    }

    @Test fun decoderFailurePropagatesInsteadOfHangingConsumer() = runBlocking {
        try {
            withTimeout(5000) {
                AnalysisPipeline.process(listOf(1), prepare = { throw IllegalArgumentException("decode") }, consume = { _: Int -> })
            }
            fail("Expected decoder failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("decode", expected.message)
        }
    }
}
