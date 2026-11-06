package dev.southstar21.vectra.platform

import dev.southstar21.vectra.features.navbar.SystemNavbar
import dev.southstar21.vectra.features.navbar.SystemNavbarState

/** Implemented in Swift (iosApp's TabBarHost): the UITabBarController drawn over the Compose app. */
interface IosTabBarHost {
    fun render(state: SystemNavbarState)
    fun cover(fraction: Float)
    fun remove()
}

/**
 * The iOS system tab bar, as the shared shell sees it. Compose pushes state in; the Swift host
 * calls [select] / [runAction] back. State that arrives before the host attaches is replayed.
 */
object IosTabBar : SystemNavbar {
    private var host: IosTabBarHost? = null
    private var last: SystemNavbarState? = null
    private var lastCover = 0f

    override var onSelect: ((Int) -> Unit)? = null
    override var onAction: ((String) -> Unit)? = null

    fun attach(host: IosTabBarHost) {
        this.host = host
        last?.let { host.render(it); host.cover(lastCover) }
    }

    override fun update(state: SystemNavbarState) {
        if (state == last) return
        last = state
        host?.render(state)
    }

    override fun setCover(fraction: Float) {
        lastCover = fraction
        host?.cover(fraction)
    }

    override fun detach() {
        last = null
        lastCover = 0f
        host?.remove()
    }

    /** A tab was tapped in the system bar. */
    fun select(index: Int) {
        onSelect?.invoke(index)
    }

    /** A + menu entry was chosen. */
    fun runAction(id: String) {
        onAction?.invoke(id)
    }
}
