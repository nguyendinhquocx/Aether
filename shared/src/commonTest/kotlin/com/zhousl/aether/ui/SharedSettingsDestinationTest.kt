package com.zhousl.aether.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedSettingsDestinationTest {
    @Test
    fun missingExtensionDestinationWaitsForSnapshotResolution() {
        assertFalse(
            shouldReturnToSettingsHubForMissingExtension(
                encodedDestination = "ExtensionSettings\nextension.settings",
                registeredExtensionSettingsIds = emptySet(),
                extensionSnapshotResolved = false,
            ),
        )
    }

    @Test
    fun registeredExtensionDestinationRemainsOpen() {
        assertFalse(
            shouldReturnToSettingsHubForMissingExtension(
                encodedDestination = "ExtensionSettings\nextension.settings",
                registeredExtensionSettingsIds = setOf("extension.settings"),
                extensionSnapshotResolved = true,
            ),
        )
    }

    @Test
    fun missingExtensionDestinationReturnsToHubAfterSnapshotResolution() {
        assertTrue(
            shouldReturnToSettingsHubForMissingExtension(
                encodedDestination = "ExtensionSettings\nextension.settings",
                registeredExtensionSettingsIds = emptySet(),
                extensionSnapshotResolved = true,
            ),
        )
    }

    @Test
    fun nonExtensionDestinationIsUnaffected() {
        assertFalse(
            shouldReturnToSettingsHubForMissingExtension(
                encodedDestination = "General\n",
                registeredExtensionSettingsIds = emptySet(),
                extensionSnapshotResolved = true,
            ),
        )
    }
}
