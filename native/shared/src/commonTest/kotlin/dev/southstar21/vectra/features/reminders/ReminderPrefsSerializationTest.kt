package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.platform.ScheduledNotification
import kotlin.test.Test
import kotlin.test.assertEquals

class ReminderPrefsSerializationTest {
    private val leg = CommuteLeg("t1", "BKK_F01", "Blaha Lujza tér", "BKK_3060", "6", "TRAM", "FFD800", "000000", "Móricz Zsigmond körtér")

    @Test fun singleLegFromBeforeListsStillLoads() {
        val legJson = VectraJson.encodeToString(CommuteLeg.serializer(), leg)
        val stored = """{"beforeClass":true,"toUni":$legJson,"backHome":null}"""
        val prefs = VectraJson.decodeFromString(ReminderPrefs.serializer(), stored)
        assertEquals(listOf(leg), prefs.toUni)
        assertEquals(emptyList(), prefs.backHome)
        assertEquals(true, prefs.beforeClass)
    }

    @Test fun listsRoundTrip() {
        val prefs = ReminderPrefs(toUni = listOf(leg, leg.copy(trackerId = "t2", routeShortName = "28A")), backHome = listOf(leg))
        val json = VectraJson.encodeToJsonElement(ReminderPrefs.serializer(), prefs)
        assertEquals(prefs, VectraJson.decodeFromJsonElement(ReminderPrefs.serializer(), json))
    }

    @Test fun alarmSetByTheOldVersionStillFires() {
        val stored = """{"id":"rem:home:x","atMs":1,"title":"t","body":"b","departures":{"stopId":"s","stopName":"Stop","routeId":"r","lineLabel":"M4"}}"""
        val n = VectraJson.decodeFromString(ScheduledNotification.serializer(), stored)
        assertEquals(listOf("M4"), n.departures.map { it.lineLabel })
    }
}
