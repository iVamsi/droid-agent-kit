package com.droidagentkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CancellationTokenTest {
    @Test
    fun `starts uncancelled`() {
        assertFalse(CancellationToken().isCancelled)
    }

    @Test
    fun `cancel runs registered hooks once`() {
        val token = CancellationToken()
        val runs = AtomicInteger()
        token.onCancel { runs.incrementAndGet() }

        token.cancel()
        token.cancel()

        assertTrue(token.isCancelled)
        assertEquals("cancelling twice must not run hooks twice", 1, runs.get())
    }

    @Test
    fun `a hook registered after cancellation runs immediately`() {
        // The process is started after the token is threaded through, so the cancel can land in
        // between. Losing the hook there would leak the process the token exists to kill.
        val token = CancellationToken()
        token.cancel()
        val runs = AtomicInteger()

        token.onCancel { runs.incrementAndGet() }

        assertEquals(1, runs.get())
    }

    @Test
    fun `a throwing hook does not stop the others`() {
        val token = CancellationToken()
        val survived = AtomicInteger()
        token.onCancel { throw IllegalStateException("process already gone") }
        token.onCancel { survived.incrementAndGet() }

        token.cancel()

        assertEquals(1, survived.get())
    }

    @Test
    fun `cancel is safe from another thread while hooks are registering`() {
        val token = CancellationToken()
        val runs = AtomicInteger()
        val start = CountDownLatch(1)
        val registrar =
            Thread {
                start.await()
                repeat(200) { token.onCancel { runs.incrementAndGet() } }
            }
        val canceller =
            Thread {
                start.await()
                token.cancel()
            }
        registrar.start()
        canceller.start()
        start.countDown()
        registrar.join(TimeUnit.SECONDS.toMillis(5))
        canceller.join(TimeUnit.SECONDS.toMillis(5))

        // Whatever the interleaving, every hook must have run exactly once: those registered
        // before the cancel run during it, those after run on registration.
        assertEquals(200, runs.get())
    }

    @Test
    fun `NONE is never cancelled`() {
        assertFalse(CancellationToken.NONE.isCancelled)
    }
}
