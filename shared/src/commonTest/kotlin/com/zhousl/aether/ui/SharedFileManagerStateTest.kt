package com.zhousl.aether.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedFileManagerStateTest {
    @Test
    fun navigateBackMovesUpUntilRootThenDeclinesToHandleBack() {
        val state = SharedFileManagerState()
        state.navigateTo("/home/user")

        assertTrue(state.navigateBack())
        assertEquals("/home", state.path)
        assertTrue(state.navigateBack())
        assertEquals("/", state.path)
        assertFalse(state.navigateBack())
        assertEquals("/", state.path)
    }
}
