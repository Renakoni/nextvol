package indi.renakoni.nextvol.data.web.rules

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.*
import hnovel.rules.OutputKind
import hnovel.rules.RuleValue
import indi.renakoni.nextvol.data.web.SourceSessionManager
import indi.renakoni.nextvol.data.web.SourceNetworkSettings
import indi.renakoni.nextvol.data.web.SourceCatalog
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RevisionError { NotInstalled, NoUpdateAddress, InvalidCandidate, IdentityChanged, ProfileChanged,
    PermissionRequired, InitializationFailed, Stale, NoPreviousRevision, UpstreamNotAdapted, UpdateDownloadFailed }
class RevisionException(val code: RevisionError, val requiredOrigin: String? = null) : Exception(code.name)
data class RevisionCheck(val installed: SourceDefinition, val preview: ImportPreview, val unchanged: Boolean)

/** Checks never execute source code. Apply validates in disposable storage before one durable activation. */
@Singleton
class SourceRevisionUpdates @Inject constructor(@ApplicationContext context: Context,
    private val sources: ImportedRuleSources, private val accounts: SourceSessionManager,
    private val runner: RuleTaskRunner, private val authority: ExecutionAuthority,
    private val networkSettings: SourceNetworkSettings? = null,
    catalog: SourceCatalog = SourceCatalog(context),
    private val downloader: SourceUpdateDownloader = SourceUpdateDownloader(context)) {
    private val temporary = File(context.cacheDir, "source-revision-checks")
    private val pixiv = PixivUpdateAdapter(catalog)

    fun manages(definition: SourceDefinition): Boolean = pixiv.manages(definition)

    fun canCheckUpdate(definition: SourceDefinition): Boolean = updateAddress(definition) != null

    private fun updateAddress(definition: SourceDefinition): String? =
        if (pixiv.manages(definition)) PixivUpdateAdapter.UPDATE_URL
        else definition.origin.takeIf { it.kind == ImportOrigin.Kind.Url }?.location?.takeIf(String::isNotBlank)

    suspend fun check(source: Identifier, downloadGrant: NetworkGrant? = null,
        expected: DefinitionReference? = null): RevisionCheck = withContext(Dispatchers.IO) {
        val installed = installed(source).definition
        if (expected != null && installed.reference() != expected) throw RevisionException(RevisionError.Stale)
        val managed = pixiv.manages(installed)
        val address = updateAddress(installed) ?: throw RevisionException(RevisionError.NoUpdateAddress)
        val downloaded = downloader.preview(sources.importer, address, installed.profile, downloadGrant)
        if (managed && downloaded.issues.any { it.code in setOf(ImportCode.DownloadFailed, ImportCode.ReadFailed) })
            throw RevisionException(RevisionError.UpdateDownloadFailed)
        val preview = if (managed) sources.importer.preview(pixiv.adapt(downloaded), installed.profile) else downloaded
        if (installed(source).definition.reference() != installed.reference()) throw RevisionException(RevisionError.Stale)
        RevisionCheck(installed, preview, preview.candidates.deduplicated().any {
            it.importKey == installed.importKey && it.rawJson == installed.rawJson
        })
    }

    /** Candidate has been explicitly selected in import preview. A changed key requires a separate explicit approval. */
    suspend fun apply(source: Identifier, candidate: DefinitionReference, approvedOrigins: List<NetworkGrant>,
        allowIdentityChange: Boolean = false) = withContext(Dispatchers.IO) {
        val before = installed(source)
        val definition = sources.definitions.list().singleOrNull { it.reference() == candidate }
            ?: throw RevisionException(RevisionError.Stale)
        if (definition.sourceId != before.definition.sourceId ||
            !allowIdentityChange && definition.importKey != before.definition.importKey) throw RevisionException(RevisionError.IdentityChanged)
        val next = if (definition.origin.kind == ImportOrigin.Kind.Paste && before.definition.origin.kind == ImportOrigin.Kind.Url)
            definition.copy(origin = before.definition.origin) else definition
        replace(before, next, approvedOrigins)
    }

    /** Permissions apply to the installed snapshot, including after rollback or a rejected import. */
    suspend fun updatePermissions(source: Identifier, approvedOrigins: List<NetworkGrant>) = withContext(Dispatchers.IO) {
        val before = installed(source)
        replace(before, before.definition, approvedOrigins)
    }

    /** The installed snapshot is authoritative; local edits do not overwrite staged import candidates. */
    suspend fun edit(source: Identifier, expected: InstalledRuleSource, text: String, approvedOrigins: List<NetworkGrant>) = withContext(Dispatchers.IO) {
        val before = installed(source)
        if (before.definition != expected.definition || before.origins != expected.origins) throw RevisionException(RevisionError.Stale)
        val revision = maxOf(expected.definition.revision, before.previous?.revision ?: 0, sources.definitions.list().filter { it.sourceId == expected.definition.sourceId }
            .maxOfOrNull { it.revision } ?: 0) + 1
        val next = SourceDefinitionEdits.prepare(expected.definition, text, revision)
        replace(before, next, approvedOrigins)
    }

    suspend fun rollback(source: Identifier, approvedOrigins: List<NetworkGrant>) = withContext(Dispatchers.IO) {
        val before = installed(source)
        val previous = before.previous ?: throw RevisionException(RevisionError.NoPreviousRevision)
        replace(before, previous, approvedOrigins)
    }

    private suspend fun replace(before: InstalledRuleSource, next: SourceDefinition, grants: List<NetworkGrant>) {
        if (next.profile != before.definition.profile) throw RevisionException(RevisionError.ProfileChanged)
        // Also guard direct update/rollback calls; a preview is not permission to restore removed code.
        if (pixiv.manages(before.definition) && next.contentDigest != before.definition.contentDigest && !pixiv.accepts(next))
            throw RevisionException(RevisionError.UpstreamNotAdapted)
        if (grants.size > 32) throw RevisionException(RevisionError.InvalidCandidate)
        if (next.contentDigest == before.definition.contentDigest && grants == before.origins) return
        val id = ImportedRuleSources.id(before.definition)
        val generation = accounts.current(id).generation
        // Stored, disabled definitions and sources awaiting grants execute no initialization code.
        if (before.preferences.enabled && grants.isNotEmpty()) validate(next, grants)
        currentCoroutineContext().ensureActive()
        if (accounts.current(id).generation != generation) throw RevisionException(RevisionError.Stale)
        sources.replaceRevision(before.definition, next, grants, generation, before.origins)
    }

    private suspend fun validate(definition: SourceDefinition, grants: List<NetworkGrant>) {
        val root = scratch()
        val namespace = "revision-validation-${UUID.randomUUID()}"
        val ticket = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, namespace)
        try {
            withTimeout(30000) {
                SourceBroker(root.toPath(), route = networkSettings?.snapshotFor(ImportedRuleSources.id(definition))).use { broker ->
                    val session = broker.open(SourceScope(namespace, definition.sourceId, definition.profile), grants)
                    val raw = Json.parseToJsonElement(definition.rawJson).jsonObject
                    val base = raw.getValue("bookSourceUrl").jsonPrimitive.content
                    if (session.permissionFailure(base) != null)
                        throw RevisionException(RevisionError.PermissionRequired, sourcePermissionOrigin(base))
                    // Adapter construction checks structure; initialization runs only in the real isolated runner.
                    RuleSource(definition, ticket, authority, session, runner).use {
                        // BookInfo may reuse the name already extracted by search/discovery.
                        for ((field, required) in mapOf("ruleToc" to "chapterList", "ruleContent" to "content")) {
                            if (raw[field]?.jsonObject?.get(required)?.jsonPrimitive?.content.isNullOrBlank())
                                throw RevisionException(RevisionError.InvalidCandidate)
                        }
                        val limits = ExecutionLimits(timeoutMillis = 10000)
                        val task = ExecutionTask.Rule("@js:true", RuleValue.Empty, OutputKind.Text,
                            baseUrl = base, libraryCode = raw["jsLib"]?.jsonPrimitive?.content)
                        val result = SourceExecutionBroker(ticket, authority, session, limits, base).use { port ->
                            runner.execute(ticket, task, limits, port)
                        }
                        if (result !is ExecutionResult.Success) throw RevisionException(RevisionError.InitializationFailed)
                    }
                }
            }
        } catch (cancelled: TimeoutCancellationException) { throw RevisionException(RevisionError.InitializationFailed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RevisionException) { throw failure }
        catch (_: Exception) { throw RevisionException(RevisionError.InvalidCandidate) }
        finally { authority.revoke(ticket); root.deleteRecursively() }
    }

    private suspend fun installed(source: Identifier) = sources.installedSources().singleOrNull {
        ImportedRuleSources.id(it.definition) == source
    } ?: throw RevisionException(RevisionError.NotInstalled)

    private fun scratch(): File {
        temporary.mkdirs()
        return File(temporary, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }
}
