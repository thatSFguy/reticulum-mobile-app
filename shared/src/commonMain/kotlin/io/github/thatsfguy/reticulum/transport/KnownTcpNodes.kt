package io.github.thatsfguy.reticulum.transport

/**
 * Curated rotation of public Reticulum TCP transport entrypoints.
 *
 * Used as the **first-launch default** for [Preferences.tcpHost] /
 * [Preferences.tcpPort]: when no value has been stored, [Preferences]
 * picks one entry at random and persists it. Subsequent launches keep
 * that pick — the random choice happens once per fresh install, not
 * per launch — so user-facing behavior stays predictable. Users can
 * re-roll explicitly via [Preferences.pickAnotherTcpNode] (wired to a
 * shuffle button in Settings).
 *
 * Rationale (origin: 2026-05-07): the prior single default —
 * `RNS.MichMesh.net:7822` — became overloaded after Columba's launch
 * concentrated traffic on it. Operator requested distribution. This
 * rotation spreads new-install attach load roughly evenly across N
 * nodes, where N is the size of [DEFAULTS], without forcing the user
 * to research the connect.html list themselves.
 *
 * **Inclusion criteria** (applied during the 2026-05-07 probe sweep
 * over the full reticulum.community/connect.html list):
 * - DNS-named (raw IPs bit-rot when the operator migrates)
 * - Responds to TCP connect within 5s from a US residential ISP
 * - Latency under ~700ms (eliminates one-second-plus shared-hosting
 *   candidates that aren't really meant for this kind of attach load)
 * - Operator profile readable from the connect.html line
 *   (organizationally clear / non-anonymous)
 *
 * Edits should re-run the probe sweep and update `2026-05-07` in this
 * comment to the new verification date. List changes only affect
 * fresh installs; persisted user choices are preserved.
 */
object KnownTcpNodes {

    /** Verified reachable 2026-06-23 — see kdoc for inclusion criteria.
     *  Expanded 2026-06-23 to mirror the web client's `hubs.json`
     *  (../reticulum-lora-webclient/hubs.json). That file carries one
     *  extra entry — a raw-IP node (ZHULONG1, HK) — deliberately omitted
     *  here because raw IPs bit-rot when operators migrate (enforced by
     *  KnownTcpNodesTest.allEntriesAreWellFormed). */
    val DEFAULTS: List<Pair<String, Int>> = listOf(
        "RNS.MichMesh.net"          to 7822,   // origin operator (Mich, US)
        "dfw.us.g00n.cloud"         to 6969,   // g00n.cloud, US East
        "use.inertia.chat"          to 4242,   // Inertia.Chat, US
        "rns.wisco.network"         to 4242,   // wisco.network, US
        "rns.beleth.net"            to 4242,   // Beleth RNS Hub
        "rns.dismail.de"            to 7822,   // dismail, DE
        "vps001.vanheusden.com"     to 4242,   // vanheusden, NL
        "phantom.mobilefabrik.com"  to 4242,   // mobilefabrik
        "reticulum.hardenedbsd.org" to 4242,   // HardenedBSD
        "istanbul.reserve.network"  to 9034,   // R-Net, Turkey
        "sydney.reticulum.au"       to 4242,   // Sydney, AU
    )

    /** Pick one entry from [DEFAULTS] at uniform random. */
    fun pickRandom(): Pair<String, Int> = DEFAULTS.random()

    /** Pick a different entry from the user's [current] choice. Falls
     *  back to a plain random pick if [current] isn't in [DEFAULTS]
     *  (e.g. user typed a custom host previously). */
    fun pickDifferentThan(current: Pair<String, Int>): Pair<String, Int> {
        val others = DEFAULTS.filter { it != current }
        return if (others.isEmpty()) pickRandom() else others.random()
    }
}
