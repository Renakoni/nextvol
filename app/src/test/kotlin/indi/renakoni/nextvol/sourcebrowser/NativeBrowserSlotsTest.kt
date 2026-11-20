package indi.renakoni.nextvol.sourcebrowser

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeBrowserSlotsTest {
    @Test fun sharedPagesOverlapButAnExclusiveOwnerWaitsForBoth() = runBlocking {
        val slots = NativeBrowserSlots(2)
        val first = slots.acquire(shared = { true })
        val second = slots.acquire(shared = { true })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { slots.exclusive { entered.complete(Unit); release.await() } }
        val later = async(start = CoroutineStart.UNDISPATCHED) { slots.acquire(shared = { true }) }
        assertFalse(entered.isCompleted)
        first.close(); yield()
        assertFalse(entered.isCompleted)
        second.close(); withTimeout(1000) { entered.await() }
        assertFalse(later.isCompleted)
        release.complete(Unit); exclusive.join()
        withTimeout(1000) { later.await() }.close()
    }

    @Test fun cancellingAHalfAdmittedExclusiveOwnerReturnsEveryPermit() = runBlocking {
        val slots = NativeBrowserSlots(2)
        val page = slots.acquire(shared = { true })
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { slots.exclusive { fail("Still owned") } }
        waiting.cancelAndJoin()
        val other = withTimeout(1000) { slots.acquire(shared = { true }) }
        page.close(); other.close()
        withTimeout(1000) { slots.exclusive { } }
    }

    @Test fun startupPreparationAndConnectionRetirementRemainExclusive() = runBlocking {
        val slots = NativeBrowserSlots(2)
        var current = true
        val first = slots.acquire(shared = { current })
        val second = slots.acquire(shared = { current })
        var exclusive = false
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { slots.acquire(shared = { current }) { exclusive = it } }
        current = false
        first.close(); yield()
        assertFalse(waiting.isCompleted)
        second.close()
        val lease = withTimeout(1000) { waiting.await() }
        assertTrue(exclusive)
        lease.single()
        val parallel = withTimeout(1000) { slots.acquire(shared = { true }) }
        lease.close(); parallel.close()
        assertTrue(runCatching { slots.acquire { throw IllegalStateException("Startup failed") } }.isFailure)
        withTimeout(1000) { slots.exclusive { } }
    }
}
