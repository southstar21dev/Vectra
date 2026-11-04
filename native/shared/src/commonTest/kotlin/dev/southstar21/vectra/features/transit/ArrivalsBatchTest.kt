package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.network.VectraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** expandStopArrivalsBatch (lives in FutarApi.kt). */
class ArrivalsBatchTest {
    private fun payload(json: String) = VectraJson.decodeFromString(FutarStopArrivalsBatchResponse.serializer(), json)

    private val full = payload(
        """
        {"currentTime":1700000000000,"status":"OK","data":{
          "shared":{
            "routes":{"BKK_5400":{"id":"BKK_5400","shortName":"M4","description":"Kelenföld vasútállomás M | Keleti pályaudvar M","type":"SUBWAY","color":"12A13C","textColor":"FFFFFF"}},
            "trips":{"trip-m4-1":{"routeId":"BKK_5400","tripHeadsign":"Keleti pályaudvar M"}},
            "alerts":{"alert1":{"id":"alert1","effectType":"DETOUR"}}
          },
          "arrives":{
            "BKK_CSF01108":{"stopId":"BKK_CSF01108",
              "stopTimes":[{"stopId":"BKK_F00073","stopHeadsign":"Keleti pályaudvar M","predictedArrivalTime":1700000180,"tripId":"trip-m4-1"}],
              "alertIds":["alert1"],
              "stops":{"BKK_F00073":{"id":"BKK_F00073","name":"Astoria M","lat":47.4944,"lon":19.0607}}},
            "BKK_CS002":{"error":"Futár returned HTTP 502"}
          }}}
        """,
    )

    @Test fun rebuildsPerStopResponses() {
        val (responses, errors) = expandStopArrivalsBatch(full)
        val entry = responses.getValue("BKK_CSF01108")
        assertEquals(1_700_000_000_000L, entry.currentTime)
        assertEquals("OK", entry.status)
        assertEquals("BKK_CSF01108", entry.data?.entry?.stopId)
        assertEquals(1, entry.data?.entry?.stopTimes?.size)
        assertEquals(listOf("alert1"), entry.data?.entry?.alertIds)
        assertEquals("M4", entry.data?.references?.routes?.get("BKK_5400")?.shortName)
        assertEquals("BKK_5400", entry.data?.references?.trips?.get("trip-m4-1")?.routeId)
        assertEquals("DETOUR", entry.data?.references?.alerts?.get("alert1")?.effectType)
        assertEquals("Astoria M", entry.data?.references?.stops?.get("BKK_F00073")?.name)
        assertEquals("Futár returned HTTP 502", errors["BKK_CS002"])
        assertNull(responses["BKK_CS002"])
    }

    @Test fun doesNotLeakOtherStopsPlatforms() {
        // `shared.stops` is not part of the native wire model at all, so it cannot leak.
        val (responses) = expandStopArrivalsBatch(
            payload("""{"currentTime":1,"data":{"shared":{"stops":{"OTHER":{"id":"OTHER","name":"Elsewhere","lat":1,"lon":2}}},"arrives":{"A":{"stopId":"A","stopTimes":[],"stops":{}}}}}"""),
        )
        assertEquals(emptyMap(), responses.getValue("A").data?.references?.stops)
    }

    @Test fun defaultsMissingCollections() {
        val (responses, errors) = expandStopArrivalsBatch(payload("""{"data":{"arrives":{"A":{"stopId":"A"}}}}"""), now = 5)
        val a = responses.getValue("A")
        assertEquals(emptyList(), a.data?.entry?.stopTimes)
        assertNull(a.data?.entry?.alertIds)
        assertEquals(FutarReferences(), a.data?.references)
        assertEquals(emptyMap(), errors)
    }

    @Test fun emptyForMissingOrMalformed() {
        val empty = StopArrivalsBatch(emptyMap(), emptyMap())
        assertEquals(empty, expandStopArrivalsBatch(null))
        for (json in listOf("{}", """{"data":{}}""", """{"data":{"arrives":null}}""")) {
            assertEquals(empty, expandStopArrivalsBatch(payload(json)))
        }
    }
}
