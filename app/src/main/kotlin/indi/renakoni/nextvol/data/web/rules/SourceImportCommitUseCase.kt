package indi.renakoni.nextvol.data.web.rules

import hnovel.imports.*
import hnovel.network.NetworkGrant
import hnovel.network.sourcePermissionOrigin
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

enum class SourceImportResult { Success, Partial }

/** Commits selected definitions, applies installed revisions, then activates new sources as one batch. */
class SourceImportCommitUseCase @Inject constructor(private val sources: ImportedRuleSources,
    private val updates: SourceRevisionUpdates) {
    suspend fun commit(preview: ImportPreview, selected: Set<Int>, permissions: Map<Int, String>,
        allowIdentityChange: Boolean, installedSnapshot: List<InstalledRuleSource>, updateTarget: Identifier?,
        groupByCatalog: Boolean, onDefinitionsCommitted: () -> Unit): SourceImportResult {
        require(selected.isNotEmpty())
        val candidates = preview.candidates.filter { it.index in selected }
        require(candidates.size == selected.size)
        val target = updateTarget?.let { id -> installedSnapshot.single { ImportedRuleSources.id(it.definition) == id } }
        if (target != null) require(candidates.size == 1)
        val grants = candidates.associate { it.index to sourceImportGrants(permissions.getValue(it.index)) }
        val selections = candidates.map { candidate ->
            val decision = if (target != null && target.definition.importKey != candidate.importKey) {
                require(allowIdentityChange); ImportDecision.MapIdentity(target.definition.reference())
            } else candidate.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add
            ImportSelection(candidate.index, decision)
        }
        val committed = sources.importer.commit(preview, selections)
        var failed = committed.error != null
        // Notify before revision/activation can suspend or fail; the caller must discard this preview.
        onDefinitionsCommitted()
        val definitions = sources.definitions.list().associateBy { it.reference() }
        val installed = installedSnapshot.associateBy { ImportedRuleSources.id(it.definition) }
        val additions = linkedMapOf<DefinitionReference, List<NetworkGrant>>()
        for (item in committed.items) {
            val reference = item.reference
            if (reference == null || item.error != null) { failed = true; continue }
            try {
                val definition = definitions.getValue(reference)
                val id = ImportedRuleSources.id(definition)
                val previous = installed[id]
                if (previous != null) {
                    if (previous.definition != definition || previous.origins != grants.getValue(item.index))
                        updates.apply(id, reference, grants.getValue(item.index), allowIdentityChange)
                } else additions[reference] = grants.getValue(item.index)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        if (additions.isNotEmpty() && sources.activateBatch(additions, enableNew = true,
                groupByCatalog = groupByCatalog).size != additions.size) failed = true
        return if (failed) SourceImportResult.Partial else SourceImportResult.Success
    }
}

internal fun sourceImportGrants(text: String): List<NetworkGrant> = text.lines().filter { it.isNotBlank() }
    .map { NetworkGrant(requireNotNull(sourcePermissionOrigin(it))) }.distinctBy { it.origin }.also { require(it.size <= 32) }
