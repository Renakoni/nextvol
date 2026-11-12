package indi.renakoni.nextvol.data.web

import indi.renakoni.nextvol.data.web.rules.SourcePreferences
import io.nightfish.lightnovelreader.api.identifier.Identifier

/** Shared list and group member, independent of the source's execution backend. */
data class ManagedSource(val id: Identifier, val name: String, val host: String, val preferences: SourcePreferences,
    val hasDiscovery: Boolean = false) {
    val key: String get() = id.toString()
}
