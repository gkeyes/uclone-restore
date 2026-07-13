package com.uclone.restore.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NavigationContractTest {
    @Test
    fun compactNavigationHasExactlyFiveTopLevelDestinations() {
        assertEquals(
            listOf(
                Destination.HOME,
                Destination.APPS,
                Destination.DATA,
                Destination.HISTORY,
                Destination.SETTINGS,
            ),
            topLevelDestinations,
        )
        assertFalse(Destination.DIAGNOSTICS in topLevelDestinations)
    }

    @Test
    fun nestedDestinationsBelongToTheirOwningTopLevelScreen() {
        assertEquals(true, Destination.DETAIL.belongsTo(Destination.APPS))
        assertEquals(true, Destination.DATA_DETAIL.belongsTo(Destination.DATA))
        assertEquals(true, Destination.DIAGNOSTICS.belongsTo(Destination.SETTINGS))
    }

    @Test
    fun nestedBackNavigationReturnsToTheExpectedOwner() {
        assertEquals(
            Destination.SETTINGS,
            navigationBackTarget(Destination.DIAGNOSTICS, Destination.HOME),
        )
        assertEquals(
            Destination.DATA,
            navigationBackTarget(Destination.DATA_DETAIL, Destination.HOME),
        )
        assertEquals(
            Destination.APPS,
            navigationBackTarget(Destination.DETAIL, Destination.APPS),
        )
    }
}
