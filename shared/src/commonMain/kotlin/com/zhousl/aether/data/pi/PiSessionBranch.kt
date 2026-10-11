package com.zhousl.aether.data.pi

/**
 * Fork the Pi transcript as part of run_turn, after its runtime is prepared.
 * With no entry ID (legacy/imported history), rebuild from the supplied prefix.
 * A null branch on the request means continue the existing Pi transcript.
 */
data class PiSessionBranch(val entryId: String? = null)
