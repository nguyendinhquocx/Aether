package com.zhousl.aether.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherSelfManagementToolRoutingTest {
    @Test
    fun scheduledTaskToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_scheduled_task_manage"))
    }

    @Test
    fun piExtensionToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_extension_manage"))
    }

    @Test
    fun createExtensionSkillIsAvailableWithoutDefaultSelection() {
        assertTrue(CreateExtensionSkillId in BuiltInAgentSkillIds)
        assertFalse(CreateExtensionSkillId in AppSettings().defaultSelectedSkillIds)
    }
}
