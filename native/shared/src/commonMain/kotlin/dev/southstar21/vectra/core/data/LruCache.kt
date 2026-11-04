package dev.southstar21.vectra.core.data

/** A size-bounded map that evicts the least recently used key. Not thread-safe. */
class LruCache<K : Any, V : Any>(private val capacity: Int) {
    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = entries.size

    operator fun get(key: K): V? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        if (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}
