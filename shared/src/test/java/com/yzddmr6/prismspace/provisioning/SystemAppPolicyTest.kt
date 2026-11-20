package com.yzddmr6.prismspace.provisioning

import com.yzddmr6.prismspace.provisioning.SystemAppOverride.Disabled
import com.yzddmr6.prismspace.provisioning.SystemAppOverride.Enabled
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.AVAILABLE
import com.yzddmr6.prismspace.provisioning.TargetState.Companion.UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppPolicyTest {

    private data class Case(
        val name: String,
        val inputs: PolicyInputs,
        val pkg: String,
        val expected: TargetState?,
    )

    private fun fact(pkg: String, system: Boolean = true, launcher: Boolean = true, installed: Boolean = true) =
        PackageFact(pkg, system, launcher, installed)

    private fun inputs(
        defaults: Set<String> = emptySet(),
        critical: Set<String> = emptySet(),
        exempt: Set<String> = emptySet(),
        overrides: Map<String, SystemAppOverride> = emptyMap(),
        facts: List<PackageFact> = emptyList(),
        blocked: Set<String> = emptySet(),
    ) = PolicyInputs(defaults, critical, exempt, overrides, facts, blocked)

    @Test fun splitQuadrantsAreSelectablePerDimension() {
        // Hide and suspend are independent platform switches: the space screen can keep an app
        // listed but unable to run, or out of the list while still allowed to work in background.
        assertEquals(
            TargetState.HIDDEN_ONLY,
            SystemAppPolicy.evaluate(inputs(
                overrides = mapOf("h" to SystemAppOverride.HiddenOnly),
                facts = listOf(fact("h")),
            ))["h"],
        )
        assertEquals(
            TargetState.SUSPENDED_ONLY,
            SystemAppPolicy.evaluate(inputs(
                overrides = mapOf("s" to SystemAppOverride.SuspendedOnly),
                facts = listOf(fact("s")),
            ))["s"],
        )
        // The persistence round-trip keeps all four values apart.
        assertEquals(SystemAppTarget.Hidden, TargetState.HIDDEN_ONLY.kind())
        assertEquals(SystemAppTarget.Suspended, TargetState.SUSPENDED_ONLY.kind())
        assertEquals(TargetState.HIDDEN_ONLY, SystemAppTarget.Hidden.state())
        assertEquals(TargetState.SUSPENDED_ONLY, SystemAppTarget.Suspended.state())
    }

    @Test fun blockedVendorServicesStayUnavailable() {
        // The .qms crash loop is caused by one of these: keeping the package hidden + suspended in
        // the space is the only way an app-level policy can stop its components from starting.
        assertEquals(
            UNAVAILABLE,
            SystemAppPolicy.evaluate(inputs(
                blocked = setOf("vendor.qti.data.txpwradmin"),
                facts = listOf(fact("vendor.qti.data.txpwradmin", launcher = false)),
            ))["vendor.qti.data.txpwradmin"],
        )
        // ...but an explicit Enable is the user unchecking it on the disable screen, so it wins.
        assertEquals(
            AVAILABLE,
            SystemAppPolicy.evaluate(inputs(
                blocked = setOf("vendor.qti.data.txpwradmin"),
                overrides = mapOf("vendor.qti.data.txpwradmin" to Enabled),
                facts = listOf(fact("vendor.qti.data.txpwradmin", launcher = false)),
            ))["vendor.qti.data.txpwradmin"],
        )
        // ...and a blocked package that is not installed here is left alone rather than fabricated.
        assertNull(
            SystemAppPolicy.evaluate(inputs(
                blocked = setOf("vendor.qti.data.txpwradmin"),
                facts = listOf(fact("vendor.qti.data.txpwradmin", installed = false)),
            ))["vendor.qti.data.txpwradmin"],
        )
    }

    @Test fun rulesByPriority() {
        val cases = listOf(
            Case("critical wins over a Disabled override",
                inputs(critical = setOf("s"), overrides = mapOf("s" to Disabled), facts = listOf(fact("s"))), "s", AVAILABLE),
            Case("critical without fact is still available",
                inputs(critical = setOf("gms")), "gms", AVAILABLE),
            Case("non-system fact is untouched even when overridden",
                inputs(overrides = mapOf("u" to Enabled), facts = listOf(fact("u", system = false))), "u", null),
            Case("non-system fact is untouched even in the default set",
                inputs(defaults = setOf("com.android.chrome"), facts = listOf(fact("com.android.chrome", system = false))),
                "com.android.chrome", null),
            Case("system IME is exempt",
                inputs(exempt = setOf("ime"), facts = listOf(fact("ime"))), "ime", null),
            // Fork: the disable screen must be able to turn an IME off after the user picked
            // another keyboard, so an explicit Disable wins over the IME exemption.
            Case("explicitly disabled IME is unavailable",
                inputs(exempt = setOf("ime"), overrides = mapOf("ime" to Disabled), facts = listOf(fact("ime"))), "ime", UNAVAILABLE),
            Case("Disabled override on installed package",
                inputs(overrides = mapOf("b" to Disabled), facts = listOf(fact("b"))), "b", UNAVAILABLE),
            Case("Disabled override on uninstalled package is untouched",
                inputs(overrides = mapOf("b" to Disabled), facts = listOf(fact("b", installed = false))), "b", null),
            Case("Disabled override without fact is untouched",
                inputs(overrides = mapOf("b" to Disabled)), "b", null),
            Case("Disabled override beats the default set",
                inputs(defaults = setOf("b"), overrides = mapOf("b" to Disabled), facts = listOf(fact("b"))), "b", UNAVAILABLE),
            Case("Enabled override",
                inputs(overrides = mapOf("c" to Enabled), facts = listOf(fact("c"))), "c", AVAILABLE),
            Case("Enabled override on uninstalled package installs it",
                inputs(overrides = mapOf("c" to Enabled), facts = listOf(fact("c", installed = false))), "c", AVAILABLE),
            Case("default package without fact is available (presence decided by the applier)",
                inputs(defaults = setOf("com.android.camera2")), "com.android.camera2", AVAILABLE),
            Case("no launcher and in no set is untouched",
                inputs(facts = listOf(fact("provider", launcher = false))), "provider", null),
            Case("other launcher system package is unavailable",
                inputs(facts = listOf(fact("market"))), "market", UNAVAILABLE),
            Case("uninstalled launcher system package is untouched",
                inputs(facts = listOf(fact("market", installed = false))), "market", null),
        )
        cases.forEach { case ->
            assertEquals(case.name, case.expected, SystemAppPolicy.evaluate(case.inputs)[case.pkg])
            if (case.expected == null) assertFalse(case.name, case.pkg in SystemAppPolicy.evaluate(case.inputs))
        }
    }

    @Test fun defaultSetIsGatedBySelectionStatus() {
        assertTrue(effectiveDefaultSet(null).isEmpty())
        assertTrue(effectiveDefaultSet(SelectionStatus.Pending).isEmpty())
        assertTrue(effectiveDefaultSet(SelectionStatus.Deferred).isEmpty())
        assertEquals(SystemAppDefaults.packages, effectiveDefaultSet(SelectionStatus.Confirmed))
    }

    @Test fun confirmingFlipsHyperOsCameraContactsAndBrowser() {
        // HyperOS test device: these three are installed, launcher-capable system packages that old
        // provisioning hid. Pending/Deferred keep them unavailable (R7); Confirmed enables them (R6).
        val facts = listOf("com.android.camera", "com.android.contacts", "com.android.browser").map { fact(it) }
        for (status in listOf(SelectionStatus.Pending, SelectionStatus.Deferred)) {
            val targets = SystemAppPolicy.evaluate(inputs(defaults = effectiveDefaultSet(status), facts = facts))
            facts.forEach { assertEquals("$status ${it.pkg}", UNAVAILABLE, targets[it.pkg]) }
        }
        val confirmed = SystemAppPolicy.evaluate(inputs(defaults = effectiveDefaultSet(SelectionStatus.Confirmed), facts = facts))
        facts.forEach { assertEquals(it.pkg, AVAILABLE, confirmed[it.pkg]) }
    }

    @Test fun defaultsCoverFileManagersThatAreAlsoCritical() {
        val critical = setOf("com.android.fileexplorer")
        val targets = SystemAppPolicy.evaluate(inputs(
            defaults = effectiveDefaultSet(SelectionStatus.Pending),
            critical = critical,
            facts = listOf(fact("com.android.fileexplorer")),
        ))
        assertEquals(AVAILABLE, targets["com.android.fileexplorer"])
    }

    @Test fun targetKinds() {
        assertEquals(SystemAppTarget.Available, AVAILABLE.kind())
        assertEquals(SystemAppTarget.Unavailable, UNAVAILABLE.kind())
    }
}
