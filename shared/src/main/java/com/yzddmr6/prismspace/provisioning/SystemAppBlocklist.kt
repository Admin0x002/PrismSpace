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
        // OPPO accessory app (earbuds). Provisioning drops it into every profile; it has no meaning
        // inside a space and polls Bluetooth/accessory state on its own.
        "com.heytap.accessory",
        // ColorOS background services: telemetry, AI glue, statistics, push channel and the
        // assistant screen. None of them is needed to run apps inside a space, and all of them keep
        // a process alive in the profile.
        "com.oplus.pantanal.ums",       // user/telemetry data service (:cardconfig included)
        "com.oplus.metis",              // "smart" AI service
        "com.oplus.statistics.rom",     // ROM statistics upload
        "com.oplus.deepthinker",        // OPPO "deepthinker" engine
        "com.coloros.sceneservice",     // ColorOS scene service
        "com.heytap.mcs",               // OPPO push channel
        "com.coloros.assistantscreen",  // assistant screen (负一屏)
        // Background-only packages: no UI the space needs, but they keep a process (or a
        // scheduler) alive for services nobody asked for in a dual space.
        "android.ext.services",                             // shared system helper services
        "com.android.adservices.api",                       // ad services (".adservices" process)
        "com.coloros.securepay",                            // secure payment service
        "com.heytap.openid",                                // OPPO open-id service
        "com.google.android.configupdater",                 // config updater
        "com.oplus.framework.network.drs",                  // network diagnostics/data service
        "com.oplus.powermonitor",                           // power monitor
        "com.oplus.thirdkit",                               // OPPO third-party kit
        "com.oppo.ctautoregist",                            // CT auto registration
        "com.qualcomm.qti.qms.service.connectionsecurity",  // Qualcomm QMS connection security
        "com.qualcomm.qti.workloadclassifier",              // Qualcomm workload classifier
        // More vendor background services seen running inside a freshly provisioned space.
        "com.oplus.aiunit",                                 // OPPO AI unit
        "com.oplus.aipaint",                                // OPPO AI paint
        "com.oplus.athena",                                 // OPPO cloud/AI glue
        "com.coloros.weather.service",                      // weather service (no UI in the space)
        "com.heytap.quicksearchbox",                        // quick search provider
        "com.coloros.ocs.opencapabilityservice",             // open capability service
        "com.oppo.instant.local.service",                   // instant app local service
    )
}
