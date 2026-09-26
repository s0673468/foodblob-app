package org.example.foodblob

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodBlobApplicationTest {
    @Test
    fun `startup completes false when initialization throws an IOException`() = runBlocking {
        val gate = CompletableDeferred<Boolean>()

        completeStartup(gate) { throw IOException("synthetic recovery write failure") }

        assertTrue(gate.isCompleted)
        assertFalse(gate.await())
    }

    @Test
    fun `startup completes true after successful initialization`() = runBlocking {
        val gate = CompletableDeferred<Boolean>()

        completeStartup(gate) { Unit }

        assertTrue(gate.await())
    }

    @Test
    fun `startup cancellation closes the gate and remains cancellation`() = runBlocking {
        val gate = CompletableDeferred<Boolean>()

        try {
            completeStartup(gate) { throw CancellationException("synthetic cancellation") }
        } catch (_: CancellationException) {
            assertFalse(gate.await())
            return@runBlocking
        }
        throw AssertionError("CancellationException was not rethrown")
    }

    @Test
    fun `widget refresh consumer retries one failed request without another signal`() = runTest {
        val requests = Channel<Unit>(Channel.UNLIMITED)
        var attempts = 0
        requests.send(Unit)
        requests.close()

        consumeWidgetUpdateRequests(requests) {
            attempts += 1
            if (attempts == 1) throw IOException("synthetic widget host failure")
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `widget refresh consumer stops after its bounded retry budget`() = runTest {
        val requests = Channel<Unit>(Channel.UNLIMITED)
        var attempts = 0
        requests.send(Unit)
        requests.close()

        consumeWidgetUpdateRequests(requests) {
            attempts += 1
            throw IOException("persistent synthetic widget host failure")
        }

        assertEquals(3, attempts)
    }

    @Test(expected = CancellationException::class)
    fun `widget refresh consumer propagates cancellation`() = runTest {
        val requests = Channel<Unit>(Channel.UNLIMITED)
        requests.send(Unit)
        requests.close()

        consumeWidgetUpdateRequests(requests) {
            throw CancellationException("synthetic cancellation")
        }
    }
}
