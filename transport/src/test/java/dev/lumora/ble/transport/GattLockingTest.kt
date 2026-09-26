package dev.lumora.ble.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The locking discipline GattConnection uses around its BluetoothGatt.
 *
 * GattConnection itself needs Android framework types and cannot be built on
 * the JVM, so this exercises the pattern against a stand-in that reports
 * use-after-release the way a real BluetoothGatt cannot. The invariant is the
 * one that matters for the ~32-client cap: close() must never release the
 * client while an operation is issuing against it.
 */
class GattLockingTest {

    /** Stands in for BluetoothGatt, recording any use after close. */
    private class FakeGatt {
        private val released = AtomicBoolean(false)
        val useAfterRelease = AtomicInteger(0)
        val operations = AtomicInteger(0)

        fun operate() {
            if (released.get()) useAfterRelease.incrementAndGet()
            // Widen the window a real native call would occupy.
            Thread.sleep(0, 50_000)
            if (released.get()) useAfterRelease.incrementAndGet()
            operations.incrementAndGet()
        }

        fun release() = released.set(true)
    }

    /** Mirrors GattConnection: a lock held across read-and-use of the field. */
    private class Guarded {
        private val lock = ReentrantLock()
        private var gatt: FakeGatt? = FakeGatt()
        val fake get() = gatt

        fun issue(): Boolean = lock.withLock {
            val g = gatt ?: return@withLock false
            g.operate()
            true
        }

        fun close(target: FakeGatt) = lock.withLock {
            target.release()
            gatt = null
        }
    }

    @Test
    fun `close cannot release the client mid-operation`() {
        repeat(20) {
            val guarded = Guarded()
            val target = guarded.fake!!
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)

            repeat(3) {
                pool.submit { start.await(); repeat(20) { guarded.issue() } }
            }
            pool.submit { start.await(); Thread.sleep(1); guarded.close(target) }

            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            // The whole point: an operation must never run against a released
            // client. Without the lock this races and the counter rises.
            assertEquals("use after release", 0, target.useAfterRelease.get())
        }
    }

    @Test
    fun `an unguarded reference does race, which is why the lock exists`() {
        // Demonstrates the failure the lock prevents, so the test above is
        // known to be testing something rather than passing vacuously.
        var observed = 0
        repeat(40) {
            val fake = FakeGatt()
            // AtomicReference stands in for the field; the race is the
            // read-then-use gap, not a visibility problem, so making the
            // reference itself safe does not close it.
            val ref = java.util.concurrent.atomic.AtomicReference<FakeGatt?>(fake)
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)

            repeat(3) {
                pool.submit {
                    start.await()
                    repeat(20) { ref.get()?.operate() }
                }
            }
            pool.submit { start.await(); Thread.sleep(1); fake.release(); ref.set(null) }

            start.countDown()
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            observed += fake.useAfterRelease.get()
        }
        assertTrue(
            "expected the unguarded version to race at least once; if this " +
                "fails the guarded test may be passing vacuously",
            observed > 0,
        )
    }
}
