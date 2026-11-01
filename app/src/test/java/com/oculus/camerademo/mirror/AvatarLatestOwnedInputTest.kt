package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class AvatarLatestOwnedInputTest {
    private class Owned(val id: Int) {
        val closes = AtomicInteger()
        fun close() { closes.incrementAndGet() }
    }
    private fun queue() = AvatarLatestOwnedInput<Owned> { it.close() }

    @Test fun replacingPendingInputKeepsOnlyTheLatestAndDisposesEverySupersededFrame() {
        val queue = queue(); val first = Owned(1); val second = Owned(2); val latest = Owned(3)
        assertTrue(queue.offer(first)); assertTrue(queue.offer(second)); assertTrue(queue.offer(latest))
        assertEquals(1,first.closes.get()); assertEquals(1,second.closes.get())
        assertEquals(0,latest.closes.get()); assertSame(latest,queue.take())
        assertFalse(queue.hasPending()); latest.close()
    }

    @Test fun slowActiveInferenceRetainsOwnershipWhilePendingFramesAreReplaced() {
        val queue = queue(); val active = Owned(1); val superseded = Owned(2); val newest = Owned(3)
        queue.offer(active); assertSame(active,queue.take())
        queue.offer(superseded); queue.offer(newest)
        queue.clear()
        assertEquals(0,active.closes.get())
        assertEquals(1,superseded.closes.get()); assertEquals(1,newest.closes.get())
        active.close(); assertEquals(1,active.closes.get())
    }

    @Test fun clearingPausedRunIsIdempotentAndAllowsANewRunToSubmit() {
        val queue = queue(); val old = Owned(1); val resumed = Owned(2)
        queue.offer(old); queue.clear(); queue.clear()
        assertEquals(1,old.closes.get()); assertNull(queue.take())
        assertTrue(queue.offer(resumed)); assertSame(resumed,queue.take())
        assertEquals(0,resumed.closes.get()); resumed.close()
    }

    @Test fun closeDisposesPendingAndRejectsFutureOwnedInputsWithoutDoubleClose() {
        val queue = queue(); val pending = Owned(1); val rejected = Owned(2)
        queue.offer(pending); queue.close(); queue.close()
        assertFalse(queue.offer(rejected)); assertNull(queue.take())
        assertEquals(1,pending.closes.get()); assertEquals(1,rejected.closes.get())
    }

    @Test fun closingQueueCannotDisposeTheImageStillUsedByActiveInference() {
        val queue = queue(); val active = Owned(1); val pending = Owned(2)
        queue.offer(active); assertSame(active,queue.take()); queue.offer(pending)
        queue.close()
        assertEquals(0,active.closes.get()); assertEquals(1,pending.closes.get())
        active.close(); assertEquals(1,active.closes.get())
    }

    @Test fun concurrentProducerAndLifecycleCloseReleaseEveryOwnedFrameExactlyOnce() {
        val queue = queue(); val frames = List(200) { Owned(it) }
        val ready = CountDownLatch(1)
        val producer = Thread {
            ready.await()
            frames.forEach { queue.offer(it) }
        }
        producer.start(); ready.countDown(); queue.close(); producer.join(5000)
        assertFalse(producer.isAlive)
        queue.close()
        assertNull(queue.take())
        assertTrue(frames.all { it.closes.get() == 1 })
    }

    @Test fun concurrentReplacementStillBoundsPendingOwnershipToOneValue() {
        val queue = queue(); val frames = List(200) { Owned(it) }; val ready = CountDownLatch(1)
        val workers = (0..1).map { side -> Thread {
            ready.await()
            frames.drop(side*100).take(100).forEach { queue.offer(it) }
        } }
        workers.forEach { it.start() }; ready.countDown(); workers.forEach { it.join(5000) }
        assertTrue(workers.none { it.isAlive })
        val active = queue.take()!!
        assertEquals(199,frames.sumOf { it.closes.get() })
        assertEquals(0,active.closes.get()); assertFalse(queue.hasPending())
        active.close(); queue.close()
        assertTrue(frames.all { it.closes.get() == 1 })
    }
}
