package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals

class LineSearchTest {
    private fun route(id: String, short: String, description: String = "", type: String = "BUS") =
        FutarRoute(id = id, shortName = short, description = description, type = type, color = "", textColor = "")

    private val catalog = searchableRoutes(
        listOf(
            route("b9", "9", "Óbuda", "BUS"),
            route("t4", "4", "Újbuda-központ", "TRAM"),
            route("h5", "H5", "Szentendre", "SUBURBAN_RAILWAY"),
            route("b9", "9", "Óbuda", "BUS"),
        ),
    )

    private fun ids(query: String, type: String = "ALL") = filterRoutes(catalog, query, type).map { it.id }

    @Test fun keepsOneRowPerIdInDisplayOrder() = assertEquals(sortRoutes(catalog.map { it.route }).map { it.id }, ids(""))

    @Test fun repeatedIdsCollapse() = assertEquals(3, catalog.size)

    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals(listOf("t4"), ids("UJBUDA"))
        assertEquals(listOf("b9"), ids(" obuda "))
    }

    @Test fun suburbanRailwayFiltersAsRail() = assertEquals(listOf("h5"), ids("", "RAIL"))

    @Test fun typeAndQueryCombine() {
        assertEquals(emptyList(), ids("szentendre", "BUS"))
        assertEquals(listOf("h5"), ids("szentendre", "RAIL"))
    }
}
