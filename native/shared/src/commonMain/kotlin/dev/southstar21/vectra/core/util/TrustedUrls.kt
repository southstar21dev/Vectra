package dev.southstar21.vectra.core.util

/**
 * The host of an `https` URL, lower-cased, or null for any other scheme, a URL with credentials
 * or backslashes (which parsers read differently), or one with a malformed authority or port.
 */
internal fun httpsHost(url: String): String? {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val authority = trimmed.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
    val unsafe = authority.any { it == '@' || it == '\\' || it.isWhitespace() }
    val port = authority.substringAfter(':', "")
    val host = authority.substringBefore(':').lowercase()
    val wellFormed = host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' } && port.all { it.isDigit() }
    return host.takeIf { !unsafe && wellFormed }
}

/** True when [host] is [domain] or one of its subdomains. */
internal fun isHostOrSubdomain(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

/** A service alert's link is opened only when it is an `https` page on bkk.hu, the label the app puts on it. */
fun isTrustedAlertUrl(url: String?): Boolean = url != null && httpsHost(url)?.let { isHostOrSubdomain(it, "bkk.hu") } == true

/** Radar tiles are fetched only from RainViewer's own `https` hosts. */
fun isTrustedRadarHost(host: String): Boolean = httpsHost(host)?.let { isHostOrSubdomain(it, "rainviewer.com") } == true
