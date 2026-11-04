package dev.southstar21.vectra.features.transit

/**
 * Whether a vertical drag should pull the whole sheet down instead of scrolling the list:
 * only when expanded (`sheetY <= 1`), moving down past `threshold`, and the list is at its
 * top (`feedScrollTop` null when the drag started outside the list).
 */
fun shouldPullSheet(sheetY: Double, deltaY: Double, feedScrollTop: Double?, threshold: Double): Boolean {
    if (sheetY > 1) return false
    if (deltaY <= threshold) return false
    return feedScrollTop == null || feedScrollTop <= 1
}

/** Tracks the click a drag synthesises so it can be swallowed without eating the next tap. */
interface ClickSuppressor {
    /** A drag started: the click it may synthesise should be swallowed. */
    fun arm()
    /** A new press started: any still-pending suppression is stale. */
    fun beginPress()
    /** True when this click belongs to a finished drag. */
    fun consume(): Boolean
}

fun createClickSuppressor(): ClickSuppressor = object : ClickSuppressor {
    private var pending = false
    override fun arm() { pending = true }
    override fun beginPress() { pending = false }
    override fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
