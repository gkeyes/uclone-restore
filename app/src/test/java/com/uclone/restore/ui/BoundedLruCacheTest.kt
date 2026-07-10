package com.uclone.restore.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BoundedLruCacheTest {
    @Test
    fun leastRecentlyUsedEntryIsEvictedAtCapacity() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 2)
        cache.put("first", 1)
        cache.put("second", 2)

        assertEquals(1, cache["first"])
        cache.put("third", 3)

        assertEquals(2, cache.size)
        assertEquals(1, cache["first"])
        assertNull(cache["second"])
        assertEquals(3, cache["third"])
    }

    @Test
    fun replacingAnEntryDoesNotGrowPastTheBound() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 1)

        cache.put("icon", 1)
        cache.put("icon", 2)

        assertEquals(1, cache.size)
        assertEquals(2, cache["icon"])
    }

    @Test
    fun nonPositiveCapacityIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            BoundedLruCache<String, Int>(maxEntries = 0)
        }
    }

    @Test
    fun cacheMissLoadsOffTheCallerThreadAndTheNextReadSkipsLoading() = runBlocking {
        val cache = BoundedLruCache<String, Int>(maxEntries = 2)
        val callerThread = Thread.currentThread()
        var loaderThread: Thread? = null
        var loadCount = 0

        val first = cache.getOrLoad("icon") {
            loaderThread = Thread.currentThread()
            loadCount += 1
            42
        }
        val second = cache.getOrLoad("icon") {
            loadCount += 1
            99
        }

        assertNotEquals(callerThread, loaderThread)
        assertEquals(42, first)
        assertEquals(42, second)
        assertEquals(1, loadCount)
    }
}
