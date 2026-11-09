package io.github.thatsfguy.reticulum.nomad

/**
 * The page-search shortcut on the Nomad node list: send a query straight
 * to a search node and open its results page, without first visiting
 * that node's search form.
 *
 * The request is the one the node's own form would send (SPEC §11.6.2):
 * a REQUEST for [NOMAD_SEARCH_PATH] whose data dict carries the typed
 * text under [NOMAD_SEARCH_FIELD]. That path and field are NomadSearch's
 * convention, not a NomadNet standard, so another search engine works
 * here only if it accepts the same request.
 */

/** Used until the user sets their own node in Settings. A destination
 *  hash is bound to the node's identity (SPEC §1.2), so unlike an
 *  announced name it can't be claimed by another node. */
const val NOMAD_SEARCH_DEFAULT_NODE = "e59960b7f9011dcdd7c1a6f14ac2c6d3"
const val NOMAD_SEARCH_PATH = "/page/q.mu"
const val NOMAD_SEARCH_FIELD = "field_query"

/**
 * The search node's hash from what the user typed or pasted: a bare
 * hash or any link to the node, such as `<hash>:/page/index.mu`.
 * Returns null for anything else. Goes through [parseLinkTarget] so
 * this accepts exactly what the rest of the browser calls a node.
 */
fun parseNomadSearchNodeInput(raw: String): String? =
    (parseLinkTarget(raw.trim()) as? LinkTarget.CrossNode)?.destHashHex

/**
 * Which node to search, from the stored setting: null (never changed)
 * means [NOMAD_SEARCH_DEFAULT_NODE], blank means the user turned page
 * search off, and anything else is their own node. Returns null when
 * page search is off.
 *
 * "Never changed" is stored as null rather than as a copy of the
 * default so that a later release can move the default for everyone
 * who didn't pick their own.
 */
fun resolveNomadSearchNode(stored: String?): String? = when {
    stored == null -> NOMAD_SEARCH_DEFAULT_NODE
    stored.isBlank() -> null
    else -> stored
}

/** Request data for a search. The query is trimmed; the server applies
 *  its own length limit. */
fun nomadSearchPostData(query: String): Map<String, String> =
    mapOf(NOMAD_SEARCH_FIELD to query.trim())
