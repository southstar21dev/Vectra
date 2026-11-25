package dev.southstar21.vectra.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedUrlsTest {
    @Test fun acceptsBkkHttpsPages() {
        assertTrue(isTrustedAlertUrl("https://bkk.hu/hirek/x"))
        assertTrue(isTrustedAlertUrl("https://www.bkk.hu/"))
        assertTrue(isTrustedAlertUrl("HTTPS://futar.bkk.hu:443/a?b=c"))
    }

    @Test fun rejectsEverythingElse() {
        assertFalse(isTrustedAlertUrl(null))
        assertFalse(isTrustedAlertUrl(""))
        assertFalse(isTrustedAlertUrl("http://bkk.hu/x"))
        assertFalse(isTrustedAlertUrl("intent://bkk.hu#Intent;end"))
        assertFalse(isTrustedAlertUrl("tel:112"))
        assertFalse(isTrustedAlertUrl("https://bkk-jegy.example/login"))
        assertFalse(isTrustedAlertUrl("https://bkk.hu.evil.example/"))
        assertFalse(isTrustedAlertUrl("https://evilbkk.hu/"))
        assertFalse(isTrustedAlertUrl("https://bkk.hu@evil.example/"))
        assertFalse(isTrustedAlertUrl("https://evil.example\\@bkk.hu/"))
        assertFalse(isTrustedAlertUrl("https://evil.example/?u=bkk.hu"))
        assertFalse(isTrustedAlertUrl("https://bkk.hu:abc/"))
    }

    @Test fun radarHostMustBeRainViewer() {
        assertTrue(isTrustedRadarHost("https://tilecache.rainviewer.com"))
        assertTrue(isTrustedRadarHost("https://tilecache.rainviewer.com/"))
        assertFalse(isTrustedRadarHost("http://tilecache.rainviewer.com"))
        assertFalse(isTrustedRadarHost("https://rainviewer.com.evil.example"))
        assertFalse(isTrustedRadarHost("https://evil.example"))
        assertEquals(null, httpsHost("ftp://x"))
    }
}
