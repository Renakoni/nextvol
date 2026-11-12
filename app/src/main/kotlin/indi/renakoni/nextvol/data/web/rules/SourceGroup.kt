package indi.renakoni.nextvol.data.web.rules

import kotlinx.serialization.Serializable
import io.nightfish.lightnovelreader.api.identifier.Identifier

/** User organization is independent of imported rules and recommendation categories. */
@Serializable
data class SourceGroup(val id: String, val name: String) {
    companion object {
        fun validName(name: String) = name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl)
    }
}

/** Read-only view of the same groups and memberships used by source management. */
data class SourceGrouping(val groups: List<SourceGroup> = emptyList(),
    val memberships: Map<Identifier, Set<String>> = emptyMap())
