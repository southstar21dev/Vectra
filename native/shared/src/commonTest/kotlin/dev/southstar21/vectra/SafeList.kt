package dev.southstar21.vectra

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * An append-only list for test fakes that record calls from the engine's and the app's threads at once:
 * a plain `mutableListOf` loses elements when two threads add together.
 */
class SafeList<T> : AbstractList<T>() {
    private val items = MutableStateFlow<List<T>>(emptyList())
    override val size: Int get() = items.value.size
    override fun get(index: Int): T = items.value[index]
    operator fun plusAssign(element: T) = items.update { it + element }
}
