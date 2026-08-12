package com.zhousl.aether.ui

import com.zhousl.aether.termux.TermuxSetupIssue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPermissionRequestTest {
    @Test
    fun requestsWhenTermuxBecomesAvailableWithoutPermission() {
        assertTrue(
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = true,
                privacyPolicyAccepted = true,
                setupIssue = TermuxSetupIssue.PermissionMissing,
                didAutoRequest = false,
            )
        )
    }

    @Test
    fun doesNotRequestBeforeStartupOrPrivacyAcceptance() {
        assertFalse(
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = false,
                privacyPolicyAccepted = true,
                setupIssue = TermuxSetupIssue.PermissionMissing,
                didAutoRequest = false,
            )
        )
        assertFalse(
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = true,
                privacyPolicyAccepted = false,
                setupIssue = TermuxSetupIssue.PermissionMissing,
                didAutoRequest = false,
            )
        )
    }

    @Test
    fun doesNotRepeatOrRequestForAnotherSetupIssue() {
        assertFalse(
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = true,
                privacyPolicyAccepted = true,
                setupIssue = TermuxSetupIssue.PermissionMissing,
                didAutoRequest = true,
            )
        )
        assertFalse(
            shouldAutoRequestTermuxPermission(
                isStartupRouteResolved = true,
                privacyPolicyAccepted = true,
                setupIssue = TermuxSetupIssue.NotInstalled,
                didAutoRequest = false,
            )
        )
    }
}
