package com.yzddmr6.prismspace.prism.compose.space

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileUnlockerTest {

    @Test fun unlockStartsTheUserBeforeAskingForCeUnlock() {
        // start-user brings a stopped profile up; unlock-user clears its credential-encrypted
        // storage. Neither alone leaves the space usable, so both must be in one transaction,
        // in that order.
        assertEquals("am start-user 12; am unlock-user 12", ProfileUnlocker.unlockCommand(12))
    }

    @Test fun rebootUsesThePowerCommandBothShellsAccept() {
        assertEquals("svc power reboot", ProfileUnlocker.rebootCommand())
    }
}
