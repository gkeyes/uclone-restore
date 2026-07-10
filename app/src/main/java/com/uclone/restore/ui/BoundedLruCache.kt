package com.uclone.restore.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BoundedLruCache<K : Any, V : Any>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    internal val size: Int
        @Synchronized get() = entries.size
}

internal suspend fun <K : Any, V : Any> BoundedLruCache<K, V>.getOrLoad(
    key: K,
    loader: () -> V,
): V {
    this[key]?.let { return it }
    return withContext(Dispatchers.IO) {
        this@getOrLoad[key] ?: loader().also { put(key, it) }
    }
}
