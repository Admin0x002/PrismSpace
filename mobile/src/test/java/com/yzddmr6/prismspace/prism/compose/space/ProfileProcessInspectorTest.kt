package com.yzddmr6.prismspace.prism.compose.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProcessInspectorTest {

    private val sample = listOf(
        "USER            PID  RSS NAME",
        "root              1  1234 init",
        "u11_a340       7488 288816 com.yzddmr6.prismspace",
        "u11_a227       8174 253724 com.android.systemui",
        "u0_a100        1234  50000 com.example.main",
        "u1_a5            99  1000 some.other.user",
        "u11_malformed",
    )

    @Test fun keepsOnlyTheRequestedUserAndSortsByMemory() {
        val rows = ProfileProcessInspector.parseProcessList(sample, ProfileProcessInspector.userPrefix(11))

        assertEquals(2, rows.size)
        assertEquals("com.yzddmr6.prismspace", rows[0].name)
        assertEquals(7488, rows[0].pid)
        assertEquals(288816L, rows[0].rssKb)
        assertEquals("com.android.systemui", rows[1].name)
    }

    @Test fun thePrefixDistinguishesUsersThatShareADigitPrefix() {
        // u1_ must not swallow u11_ rows, and vice versa.
        assertEquals(
            listOf("some.other.user"),
            ProfileProcessInspector.parseProcessList(sample, ProfileProcessInspector.userPrefix(1)).map { it.name },
        )
        assertTrue(ProfileProcessInspector.parseProcessList(emptyList(), ProfileProcessInspector.userPrefix(11)).isEmpty())
    }
}
