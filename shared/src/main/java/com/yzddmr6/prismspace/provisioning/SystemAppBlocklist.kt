package com.yzddmr6.prismspace.provisioning

/**
 * System/vendor packages that are useless in a managed profile and actively harmful there.
 *
 * They are kept unavailable (hidden + suspended) in every dual space, regardless of the user's
 * selection and of whether the package has a launcher entry, so a space only runs components it
 * actually needs. A blocked package the user explicitly enables through the selection page still
 * loses to the policy, same as a critical package loses to nothing: blocked wins.
 *
 * Every entry needs a reason — this list is not a general "disable anything noisy" bucket.
 */
object SystemAppBlocklist {
    val packages: Set<String> = linkedSetOf(
        // Qualcomm TxPowerAdmin (.qms process). Its ProxyService binds its own .StxPwrService with
        // FLAG_SINGLE_USER, which needs INTERACT_ACROSS_USERS — a permission app processes do not
        // get in a secondary user. The result is an endless crash/restart loop inside the space
        // (thousands of crashes per minute, with log flooding and battery drain) that the profile
        // cannot use or stop from the UI.
        "vendor.qti.data.txpwradmin",
    )
}
