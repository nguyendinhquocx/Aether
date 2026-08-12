package com.zhousl.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedTabletLayoutTest {
    @Test
    fun suppressesRegistrationDuringCloseAndRecoversIfCloseIsCanceled() {
        val gate = SharedDrawerOpenedEventGate()

        assertFalse(gate.onMobileDrawerOpened(eventRegistered = false))
        assertFalse(
            gate.onLayoutRegistrationOrDrawerSnapshotChanged(
                useTabletLayout = false,
                currentOpen = true,
                targetOpen = false,
                eventRegistered = true,
            )
        )
        assertTrue(
            gate.onLayoutRegistrationOrDrawerSnapshotChanged(
                useTabletLayout = false,
                currentOpen = true,
                targetOpen = true,
                eventRegistered = true,
            )
        )
        assertFalse(
            gate.onLayoutRegistrationOrDrawerSnapshotChanged(
                useTabletLayout = false,
                currentOpen = true,
                targetOpen = true,
                eventRegistered = true,
            )
        )
    }

    @Test
    fun usesTabletLayoutOnlyForSupportedWideScreens() {
        assertTrue(shouldUseSharedTabletLayout(supportsTabletLayout = true, availableWidthDp = 700f))
        assertTrue(shouldUseSharedTabletLayout(supportsTabletLayout = true, availableWidthDp = 1_024f))
        assertFalse(shouldUseSharedTabletLayout(supportsTabletLayout = true, availableWidthDp = 699f))
        assertFalse(shouldUseSharedTabletLayout(supportsTabletLayout = false, availableWidthDp = 1_024f))
    }

    @Test
    fun restoresSettingsUsingThePresentationForTheCurrentLayout() {
        assertEquals(
            SharedNavigationPresentation(
                route = SharedRoute.Chat,
                tabletSettingsVisible = true,
            ),
            resolveSharedNavigationPresentation(
                route = SharedRoute.Settings,
                tabletSettingsVisible = false,
                useTabletLayout = true,
            ),
        )
        assertEquals(
            SharedNavigationPresentation(
                route = SharedRoute.Settings,
                tabletSettingsVisible = false,
            ),
            resolveSharedNavigationPresentation(
                route = SharedRoute.Chat,
                tabletSettingsVisible = true,
                useTabletLayout = false,
            ),
        )
    }

    @Test
    fun defersAndCoalescesMobileDrawerEventsUntilRegistration() {
        val gate = SharedDrawerOpenedEventGate()

        assertEquals(
            expected = listOf(false, false, true, false, true),
            actual = listOf(
                gate.onMobileDrawerOpened(eventRegistered = false),
                gate.onMobileDrawerOpened(eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = false, eventRegistered = true),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = false, eventRegistered = true),
                gate.onMobileDrawerOpened(eventRegistered = true),
            ),
        )
    }

    @Test
    fun discardsDeferredMobileEventWhenLayoutChanges() {
        val gate = SharedDrawerOpenedEventGate()

        assertEquals(
            expected = listOf(false, false, false, false),
            actual = listOf(
                gate.onMobileDrawerOpened(eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = false, eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = false, eventRegistered = true),
            ),
        )
    }

    @Test
    fun discardsDeferredMobileEventWhenDrawerCloses() {
        val gate = SharedDrawerOpenedEventGate()

        assertFalse(gate.onMobileDrawerOpened(eventRegistered = false))
        assertFalse(
            gate.onLayoutRegistrationOrDrawerSnapshotChanged(
                useTabletLayout = false,
                currentOpen = true,
                targetOpen = false,
                eventRegistered = true,
            )
        )
        assertFalse(
            gate.onLayoutRegistrationOrDrawerSnapshotChanged(
                useTabletLayout = false,
                currentOpen = false,
                targetOpen = false,
                eventRegistered = true,
            )
        )
    }

    @Test
    fun dispatchesTabletDrawerEventOncePerLayoutEpoch() {
        val gate = SharedDrawerOpenedEventGate()

        assertEquals(
            expected = listOf(false, true, false, false, false, true),
            actual = listOf(
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = true),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = false),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = true),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = false, eventRegistered = true),
                gate.onLayoutOrRegistrationChanged(useTabletLayout = true, eventRegistered = true),
            ),
        )
    }

    @Test
    fun settingsDismissGuardTracksOnlyTheActiveDraft() {
        val guard = SharedSettingsDismissGuard()
        val firstPage = Any()
        val secondPage = Any()

        guard.report(firstPage, hasChanges = true)
        assertTrue(guard.hasUnsavedChanges)
        guard.rejectDismiss()
        assertTrue(guard.saveShakeRequest == 1)

        guard.report(secondPage, hasChanges = false)
        guard.clear(firstPage)
        assertFalse(guard.hasUnsavedChanges)
    }
}
