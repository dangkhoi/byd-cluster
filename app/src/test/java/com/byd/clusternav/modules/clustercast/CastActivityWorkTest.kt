package com.byd.clusternav.modules.clustercast

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastActivityWorkTest {
    @Test
    fun `selection lane preserves submission order and only latest revision may deliver`() {
        val work = CastActivityWork()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            val firstRevision = work.selection {
                firstEntered.countDown()
                releaseFirst.await(1, TimeUnit.SECONDS)
                order += "first"
                completed.countDown()
            }
            assertTrue(firstEntered.await(500, TimeUnit.MILLISECONDS))
            val secondRevision = work.selection {
                order += "second"
                completed.countDown()
            }
            assertFalse(work.isCurrentSelection(firstRevision))
            assertTrue(work.isCurrentSelection(secondRevision))
            releaseFirst.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second"), order)
        } finally {
            releaseFirst.countDown()
            work.close()
        }
    }

    @Test
    fun `new refresh revision invalidates an older computation before delivery`() {
        val work = CastActivityWork()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        try {
            var oldMayDeliver = true
            val old = work.refresh { revision ->
                firstEntered.countDown()
                releaseFirst.await(1, TimeUnit.SECONDS)
                oldMayDeliver = work.isCurrentRefresh(revision)
            }
            assertTrue(firstEntered.await(500, TimeUnit.MILLISECONDS))
            val latest = work.refresh { secondDone.countDown() }
            assertFalse(work.isCurrentRefresh(old))
            assertTrue(work.isCurrentRefresh(latest))
            releaseFirst.countDown()
            assertTrue(secondDone.await(1, TimeUnit.SECONDS))
            assertFalse(oldMayDeliver)
        } finally {
            releaseFirst.countDown()
            work.close()
        }
    }

    @Test
    fun `mutation generation invalidates pre-operation and in-flight refresh snapshots`() {
        val work = CastActivityWork()
        try {
            val before = work.mutationSnapshot()
            val first = work.beginMutation()
            val duringFirst = work.mutationSnapshot()
            assertFalse(work.isCurrentMutation(before))
            assertTrue(duringFirst.pending)

            val second = work.beginMutation()
            val duringSecond = work.mutationSnapshot()
            assertFalse(work.finishMutation(first), "older completion must not clear newer mutation fence")
            assertTrue(work.isCurrentMutation(duringSecond))
            assertTrue(work.finishMutation(second))
            assertFalse(work.isCurrentMutation(duringFirst))
            assertFalse(work.mutationSnapshot().pending)
        } finally {
            work.close()
        }
    }

    @Test
    fun `close invalidates revisions and suppresses queued delivery`() {
        val work = CastActivityWork()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRan = CountDownLatch(1)
        val revision = work.refresh {
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
        work.refresh { queuedRan.countDown() }
        work.close()
        release.countDown()

        assertFalse(work.isCurrentRefresh(revision))
        assertFalse(queuedRan.await(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `accepted selection survives teardown and precedes recreated activity reconciliation`() {
        val oldActivity = CastActivityWork()
        val recreatedActivity = CastActivityWork()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val durable = java.util.concurrent.atomic.AtomicReference("com.example.old")
        val resumedTarget = java.util.concurrent.atomic.AtomicReference<String>()
        try {
            oldActivity.selection {
                entered.countDown()
                release.await(1, TimeUnit.SECONDS)
                durable.set("com.example.latest")
            }
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
            oldActivity.close()
            recreatedActivity.selection { resumedTarget.set(durable.get()); resumed.countDown() }
            release.countDown()
            assertTrue(resumed.await(1, TimeUnit.SECONDS))
            assertEquals("com.example.latest", resumedTarget.get())
        } finally {
            release.countDown(); oldActivity.close(); recreatedActivity.close()
        }
    }

    @Test
    fun `accepted Stop survives teardown while queued`() {
        val work = CastActivityWork()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        work.stop { entered.countDown(); release.await(1, TimeUnit.SECONDS) }
        assertTrue(entered.await(500, TimeUnit.MILLISECONDS))
        work.stop { stopped.countDown() }
        work.close(); release.countDown()
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
    }
}
