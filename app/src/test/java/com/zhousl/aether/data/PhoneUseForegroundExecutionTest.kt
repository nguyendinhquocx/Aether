package com.zhousl.aether.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseForegroundExecutionTest {
    @Test fun phoneUseContinuesWhenItLaunchesAnotherAppWithBackgroundPreferenceOff() {
        val settings = AppSettings(keepTasksRunningInBackground = false)
        val running = SessionExecutionState("phone", isRunning = true, phoneUseEnabled = true)
        assertTrue(needsForegroundExecutionService(settings, mapOf("phone" to running)))
        assertFalse(needsForegroundExecutionService(settings, mapOf("phone" to running.copy(isRunning = false))))
    }

    @Test fun ordinaryTurnsContinueToRespectTheBackgroundPreference() {
        val states = mapOf("chat" to SessionExecutionState("chat", isRunning = true))
        assertFalse(needsForegroundExecutionService(AppSettings(keepTasksRunningInBackground = false), states))
        assertTrue(needsForegroundExecutionService(AppSettings(keepTasksRunningInBackground = true), states))
    }

    @Test fun anIdlePhoneUseChatDoesNotKeepOtherOrdinaryTasksAlive() {
        val states = mapOf(
            "phone" to SessionExecutionState("phone", isRunning = false, phoneUseEnabled = true),
            "chat" to SessionExecutionState("chat", isRunning = true),
        )
        assertFalse(needsForegroundExecutionService(AppSettings(keepTasksRunningInBackground = false), states))
    }
}
