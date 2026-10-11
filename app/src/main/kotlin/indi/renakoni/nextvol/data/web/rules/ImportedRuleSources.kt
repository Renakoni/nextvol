package indi.renakoni.nextvol.data.web.rules

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import hnovel.network.StorageArea
import hnovel.network.StorageRequest
import hnovel.network.StorageResult
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns installed definitions, user preferences and runtime bindings. Import preview/commit grants no authority. */
@Singleton
class ImportedRuleSources @Inject constructor(@ApplicationContext private val context: Context,
    private val registry: WebSourceRegistry, private val authority: ExecutionAuthority,
    private val accounts: SourceSessionManager, private val runner: RuleTaskRunner,
    private val storageCipher: hnovel.network.StorageCipher = hnovel.network.StorageCipher.Plain,
    private val browser: hnovel.network.BrowserExecutor? = null,
    private val verification: SourceVerificationCoordinator? = null,
    private val networkSettings: SourceNetworkSettings? = null,
    private val catalog: SourceCatalog = SourceCatalog(context),
    private val downloads: indi.renakoni.nextvol.data.download.BookDownloadStore? = null) {
    private val directory = File(context.filesDir, "rule-sources")
    val definitions by lazy { SourceDefinitionStore(File(directory, "definitions").toPath()) }
    val importer by lazy { SourceDefinitionImporter(definitions) }
    private val pixivBlocking by lazy { PixivUpdateAdapter(catalog) }
    private val committed = AtomicFile(File(directory, "active.json"))
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = linkedMapOf<Identifier, Binding>()
    private var groups: List<SourceGroup> = emptyList()
    private var restored = false
    var restorationFailed = false
        private set

    init {
        scope.launch {
            accounts.changes.collect { generations -> lock.withLock {
                for ((id, generation) in generations) {
                    val current = active[id] ?: continue
                    if (current.session != null && current.session.scope.accountGeneration != generation) {
                        downloads?.revokeSourceTasks(id)
                        runCatching { current.session.clearAccount() }.onFailure {
                            android.util.Log.w("ImportedRuleSources", "Retired account cleanup failed")
                        }
                        current.broker?.close()
                        active[id] = restoreBinding(current.installed, current).also { next ->
                            next.session?.inheritCaches(current.session)
                        }
                    }
                }
            } }
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock {
        if (restored) return@withLock
        val installed = try {
            committed.openRead().use {
                check(it.channel.size() <= MAX_SNAPSHOT_BYTES) { "Installed source snapshot exceeds quota" }
                val json = Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8))
                val snapshot = if (json is JsonArray) InstalledSnapshot(
                    Json.decodeFromJsonElement(ListSerializer(InstalledSource.serializer()), json))
                else Json.decodeFromJsonElement(InstalledSnapshot.serializer(), json)
                check(snapshot.groups.map { it.id }.distinct().size == snapshot.groups.size)
                check(snapshot.groups.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == snapshot.groups.size)
                check(snapshot.groups.all { it.id.isNotBlank() && SourceGroup.validName(it.name) && it.name == it.name.trim() })
                check(snapshot.sources.all { row -> row.preferences().groupIds.all { id -> snapshot.groups.any { it.id == id } } })
                check(snapshot.sources.size <= ImportLimits().maxStoredEntries &&
                    snapshot.sources.map { it.definition.sourceId }.distinct().size == snapshot.sources.size)
                groups = snapshot.groups
                snapshot.sources
            }
        } catch (_: java.io.FileNotFoundException) { emptyList() }
        catch (_: Exception) {
            restorationFailed = true
            emptyList()
        }
        val classified = installed.map { original ->
            val entry = repairBundledRevision(original)
            val preferences = entry.preferences()
            val category = catalog.entry(entry.definition)?.category ?: preferences.category
            if (category == preferences.category) entry else entry.copy(preferences = preferences.copy(category = category))
        }
        if (classified != installed) save(classified)
        classified.forEach { entry -> active[id(entry.definition)] = restoreBinding(entry) }
        restored = true
    } }

    private fun repairBundledRevision(installed: InstalledSource): InstalledSource {
        val before = installed.definition
        if (before.contentDigest in installed.bundledRepairs) return installed
        val raw = catalog.replacement(before) ?: return installed
        return try {
            val preview = importer.preview(raw, before.profile)
            val candidate = preview.candidates.singleOrNull()?.takeIf {
                preview.issues.isEmpty() && it.importKey == before.importKey && it.profile == before.profile
            } ?: return installed
            val stored = definitions.list().singleOrNull { it.sourceId == before.sourceId } ?: return installed
            if (stored.profile != before.profile || stored.importKey != before.importKey) return installed
            // Preserve a pending custom import. A matching repair in the store also recovers
            // interruption between the definition commit and the activation snapshot write.
            if (stored.contentDigest != before.contentDigest && stored.rawJson != candidate.rawJson) return installed
            val next = if (stored.rawJson == candidate.rawJson) stored else {
                if (candidate.existing != stored.reference()) return installed
                val result = importer.commit(preview, listOf(ImportSelection(candidate.index, ImportDecision.Replace(stored.reference()))))
                val reference = result.items.singleOrNull()?.takeIf { result.error == null && it.error == null }?.reference
                    ?: return installed
                definitions.list().singleOrNull { it.reference() == reference } ?: return installed
            }
            installed.copy(definition = next.copy(origin = before.origin),
                previous = SavedRevision(before, installed.origins),
                bundledRepairs = installed.bundledRepairs + before.contentDigest)
        } catch (_: Exception) {
            android.util.Log.w("ImportedRuleSources", "Bundled source repair remains pending")
            installed
        }
    }

    /** The caller explicitly approves origins after preview; pending revisions are not activated implicitly. */
    suspend fun activate(reference: DefinitionReference, approvedOrigins: List<NetworkGrant>): Identifier = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            check(!restorationFailed) { "Installed source snapshot could not be restored" }
            val definition = definitions.list().singleOrNull { it.reference() == reference }
                ?: error("Definition preview is no longer current")
            require(approvedOrigins.size <= 32)
            val identity = id(definition)
            active[identity]?.let {
                check(it.installed.definition == definition && it.installed.origins == approvedOrigins) { "Revision/grant replacement belongs to the update service" }
                return@withLock identity
            }
            val installed = InstalledSource(definition, approvedOrigins.map { it.copy(headers = it.headers.toMap()) },
                preferences = SourcePreferences(definition.enabled, definition.enabledExplore,
                    category = catalog.entry(definition)?.category))
            // Constructing the adapter opens no network and runs no source code.
            val previous = active.values.map { it.installed }
            var saved = false
            val binding = try { bind(installed) { save(previous + installed); saved = true } }
                catch (failure: Exception) {
                    if (saved) save(previous)
                    throw failure
                }
            active[identity] = binding
            identity
        }
    }

    /** One definition read and one durable write for a selected collection. */
    suspend fun activateBatch(references: Map<DefinitionReference, List<NetworkGrant>>, enableNew: Boolean = false,
        groupByCatalog: Boolean = false): List<Identifier> = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            check(!restorationFailed)
            val current = definitions.list().associateBy { it.reference() }
            val nextGroups = groups.toMutableList()
            val additions = references.map { (reference, origins) ->
                val definition = checkNotNull(current[reference]) { "Definition preview is no longer current" }
                require(origins.size <= 32 && id(definition) !in active)
                val hasExploreUrl = Json.parseToJsonElement(definition.rawJson).jsonObject["exploreUrl"]
                    ?.jsonPrimitive?.content?.isNotBlank() == true
                val category = catalog.entry(definition)?.category
                val groupId = if (groupByCatalog && category != null) {
                    val id = "catalog:${category.name}"
                    val name = context.getString(category.title)
                    (nextGroups.firstOrNull { it.id == id }
                        ?: nextGroups.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: SourceGroup(id, name).also { nextGroups.add(it) }).id
                } else null
                InstalledSource(definition, origins.map { it.copy(headers = it.headers.toMap()) },
                    preferences = if (enableNew) SourcePreferences(true, definition.enabledExplore || hasExploreUrl,
                        enabledSetByUser = true, category = category, groupIds = setOfNotNull(groupId))
                    else SourcePreferences(definition.enabled, definition.enabledExplore, category = category, groupIds = setOfNotNull(groupId)))
            }
            // Save once for a collection; opening one definition must not rewrite thousands of others.
            save(active.values.map { it.installed } + additions, nextGroups)
            groups = nextGroups
            additions.mapNotNull { installed ->
                val identity = id(installed.definition)
                val binding = restoreBinding(installed)
                active[identity] = binding
                identity.takeIf { binding.registration != null || !installed.preferences().enabled || installed.origins.isEmpty() }
            }
        }
    }

    /** Saved books, progress and readable caches belong to the host and survive removal. */
    suspend fun remove(source: Identifier) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
        val old = active[source] ?: return@withLock
        downloads?.revokeSourceTasks(source)
        val previousMode = networkSettings?.mode(source)
        networkSettings?.setBypassVpn(source, false)
        try { save(active.filterKeys { it != source }.values.map { it.installed }) }
        catch (failure: Exception) {
            if (previousMode == hnovel.network.SourceNetworkMode.BypassVpn) networkSettings?.setBypassVpn(source, true)
            throw failure
        }
        old.registration?.unregister()
        old.broker?.close()
        active.remove(source)
    } }

    suspend fun installedSources(): List<InstalledRuleSource> = withContext(Dispatchers.IO) {
        restore()
        lock.withLock { active.values.map { InstalledRuleSource(it.installed.definition,
            it.installed.origins, it.installed.previous?.definition, it.session?.deniedOrigins.orEmpty(), it.installed.preferences()) } }
    }

    suspend fun sourceGroups(): List<SourceGroup> = withContext(Dispatchers.IO) {
        restore()
        lock.withLock { groups.toList() }
    }

    suspend fun createGroup(name: String, members: Set<Identifier> = emptySet(),
        added: Set<String> = emptySet(), removed: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val group = SourceGroup(java.util.UUID.randomUUID().toString(), checkedGroupName(name))
            require(members.all { it in active })
            require((added + removed).all { id -> groups.any { it.id == id } } && added.intersect(removed).isEmpty())
            saveGrouping(groups + group, members.associateWith {
                (active.getValue(it).installed.preferences().groupIds + added + group.id) - removed
            })
        }
    }

    suspend fun renameGroup(groupId: String, name: String) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            require(groups.any { it.id == groupId })
            val nextName = checkedGroupName(name, groupId)
            saveGrouping(groups.map { if (it.id == groupId) it.copy(name = nextName) else it })
        }
    }

    suspend fun deleteGroup(groupId: String) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            require(groups.any { it.id == groupId })
            val members = active.filterValues { groupId in it.installed.preferences().groupIds }.keys
            saveGrouping(groups.filterNot { it.id == groupId },
                members.associateWith { active.getValue(it).installed.preferences().groupIds - groupId })
        }
    }

    suspend fun updateGroups(members: Set<Identifier>, added: Set<String>, removed: Set<String>) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            require(members.isNotEmpty() && members.all { it in active })
            require((added + removed).all { id -> groups.any { it.id == id } } && added.intersect(removed).isEmpty())
            saveGrouping(groups, members.associateWith { (active.getValue(it).installed.preferences().groupIds + added) - removed })
        }
    }

    private fun checkedGroupName(name: String, exceptId: String? = null): String = name.trim().also { trimmed ->
        require(SourceGroup.validName(trimmed))
        require(groups.none { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) })
    }

    /** One durable snapshot for groups and membership; grouping never recreates a runtime or account. */
    private fun saveGrouping(nextGroups: List<SourceGroup>, memberships: Map<Identifier, Set<String>> = emptyMap()) {
        check(!restorationFailed)
        val next = active.mapValues { (id, binding) ->
            if (id !in memberships) binding else binding.copy(installed = binding.installed.copy(
                preferences = binding.installed.preferences().copy(groupId = null, groupIds = memberships.getValue(id))))
        }
        save(next.values.map { it.installed }, nextGroups)
        groups = nextGroups
        active.putAll(next)
    }

    /** Validated candidate is compared again at commit; remove/account changes cannot resurrect it. */
    internal suspend fun replaceRevision(expected: SourceDefinition, next: SourceDefinition, origins: List<NetworkGrant>,
        generation: Long) = withContext(Dispatchers.IO) { lock.withLock {
        check(!restorationFailed)
        val id = id(expected)
        val old = checkNotNull(active[id]) { "Source is not installed" }
        check(old.installed.definition == expected) { "Installed revision changed" }
        require(next.sourceId == expected.sourceId && next.profile == expected.profile && origins.size <= 32)
        if (next != expected || origins != old.installed.origins) downloads?.revokeSourceTasks(id)
        val installed = InstalledSource(next, origins.map { it.copy(headers = it.headers.toMap()) },
            if (next == expected) old.installed.previous else SavedRevision(expected, old.installed.origins), old.installed.preferences(),
            old.installed.bundledRepairs)
        accounts.withCurrent(id) { account ->
            check(account.generation == generation) { "Account changed during validation" }
            replace(old, installed)
        }
    } }

    /** Preferences belong to the installed identity, not to a particular imported revision. */
    suspend fun setPreferences(source: Identifier, enabled: Boolean? = null, discoveryVisible: Boolean? = null) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            check(!restorationFailed)
            val old = checkNotNull(active[source]) { "Source is not installed" }
            val current = old.installed.preferences()
            val next = current.copy(enabled = enabled ?: current.enabled,
                discoveryVisible = discoveryVisible ?: current.discoveryVisible,
                enabledSetByUser = enabled != null || current.enabledSetByUser)
            if (next == current) return@withLock
            if (!next.enabled) downloads?.revokeSourceTasks(source)
            replace(old, old.installed.copy(preferences = next))
        }
    }

    private fun replace(old: Binding, installed: InstalledSource) {
        val previous = active.values.map { it.installed }
        var saved = false
        val next = try { bind(installed, old) {
            save(active.values.map { if (it === old) installed else it.installed }); saved = true
        } } catch (failure: Exception) {
            if (saved) save(previous)
            throw failure
        }
        active[id(installed.definition)] = next
        old.broker?.close()
    }

    private fun restoreBinding(installed: InstalledSource, previous: Binding? = null): Binding = try { bind(installed, previous) }
    catch (_: Exception) {
        previous?.registration?.unregister()
        Binding(installed, null, null)
    }

    private fun bind(installed: InstalledSource, previous: Binding? = null, beforePublish: () -> Unit = {}): Binding = accounts.withCurrent(id(installed.definition)) { account ->
        val definition = installed.definition
        val preferences = installed.preferences()
        require(definition.profile in setOf(LEGADO_PROFILE, EXTENSION_PROFILE))
        val id = id(definition)
        val generation = account.generation
        if (!preferences.enabled || installed.origins.isEmpty()) {
            beforePublish()
            previous?.registration?.unregister()
            // Keep same-account in-memory cookies/caches for re-enable, but close all work below.
            return@withCurrent Binding(installed, null, null, previous?.session?.takeIf { it.scope.accountGeneration == generation })
        }
        val broker = SourceBroker(File(directory, "runtime").toPath(), cipher = storageCipher, browser = browser,
            route = networkSettings?.forSource(id))
        val session = try { broker.open(SourceScope(id.namespace, id.id, definition.profile, generation), installed.origins).also {
            if (pixivBlocking.accepts(definition)) PixivPreferenceStore.read(it)
        } }
            catch (failure: Exception) { broker.close(); throw failure }
        val ticket = authority.issue(id.id, definition.profile, definition.contentDigest, id.namespace, generation)
        val trace = if (!indi.renakoni.nextvol.BuildConfig.DEBUG) hnovel.content.ContentTrace.None else hnovel.content.ContentTrace { event ->
            if (indi.renakoni.nextvol.BuildConfig.DEBUG && event.result != "Success")
                android.util.Log.d("RuleSourceTrace", "source=${id.id} field=${event.field} result=${event.result}" +
                    " ruleCode=${event.ruleCode} input=${event.inputSize} output=${event.outputSize} elapsedMs=${event.elapsedMillis}" +
                    (event.requestDiagnostic?.let { " requestDiagnostic=$it" } ?: ""))
        }
        val source = try { RuleSource(definition, ticket, authority, session, runner, trace, discoveryEnabled = preferences.discoveryVisible) }
            catch (failure: Exception) { authority.revoke(ticket); broker.close(); throw failure }
        val metadata = SourceMetadata(WebDataSourceItem(id, catalog.entry(definition)?.name ?: definition.displayName, "Imported source"), buildSet {
            addAll(listOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent, SourceCapability.Images))
            if (source.canSearch) add(SourceCapability.Search)
            if (source.canLogin) add(SourceCapability.Login)
            if (source.canFeed) add(SourceCapability.Explore)
            if (source.canCategorize) add(SourceCapability.Categories)
        }, revision = definition.contentDigest, accountGeneration = generation, category = preferences.category)
        val publish = {
            previous?.session?.takeIf { it.scope == session.scope }?.let { session.inheritCookies(it); session.inheritCaches(it) }
            beforePublish()
        }
        val recovery = verification?.let { RuleRequestRecovery(it,
            VerificationOwner(id, definition.contentDigest, generation), definition.displayName) }
        val pixivFilter = if (pixivBlocking.accepts(definition)) PixivBookFilter(session) else null
        val registration = try {
            val oldRegistration = previous?.registration
            if (oldRegistration == null) { publish(); registry.register(RuleWebBookDataSource(id, source, recovery, pixivFilter), metadata) }
            else registry.replace(oldRegistration, RuleWebBookDataSource(id, source, recovery, pixivFilter), metadata, ticket, publish)
        }
            catch (failure: Exception) { source.close(); broker.close(); throw failure }
        Binding(installed, registration, broker, session, source)
    }

    internal fun refreshDiscovery(id: Identifier) = registry.refreshDiscovery(id)

    internal suspend fun loginTarget(id: Identifier): RuleLoginTarget = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            RuleLoginTarget(id, checkNotNull(current.registration).metadata.revision, current.registration.metadata.accountGeneration,
                checkNotNull(current.rule), checkNotNull(current.session))
        }
    }

    /** Fixed host settings remain accessible without loading a source or granting network access. */
    internal suspend fun storedSettings(id: Identifier, accountNameField: String? = null): RuleStoredSettings = withStoredSession(id) { session ->
        fun read(area: StorageArea, key: String) =
            (session.read(StorageRequest(area, key)) as? StorageResult.Value
                ?: error("Stored source settings are unavailable")).value
        val definition = active.getValue(id).installed.definition
        val status = SourceLoginService.storedStatus(session,
            pixiv = definition.profile == EXTENSION_PROFILE && definition.importKey == PixivUpdateAdapter.KEY)
        val name = if (accountNameField != null && status in setOf("authenticated", "session")) {
            val info = (session.read(StorageRequest(StorageArea.Account, hnovel.network.StorageRequestKey.LOGIN_INFO)) as? StorageResult.Value)?.value
            SourceLoginService.savedAccountName(accountNameField, info)
        } else null
        RuleStoredSettings(read(StorageArea.Config, "variable").orEmpty(), status, name, session.certificateExceptions())
    }

    internal suspend fun revokeCertificate(id: Identifier, origin: String) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            accounts.withCurrent(id) { account ->
                try {
                    val live = current.session?.takeIf { !it.closed && it.scope.accountGeneration == account.generation }
                    if (live != null) live.revokeCertificate(origin)
                    else SourceBroker(File(directory, "runtime").toPath(), cipher = storageCipher).use { broker ->
                        broker.open(SourceScope(id.namespace, id.id, current.installed.definition.profile, account.generation),
                            emptyList()).revokeCertificate(origin)
                    }
                } catch (failure: Exception) {
                    current.registration?.unregister()
                    current.broker?.close()
                    throw failure
                }
                current.broker?.close()
                active[id] = restoreBinding(current.installed, current)
            }
        }
    }

    internal suspend fun saveVariable(id: Identifier, value: String) = withStoredSession(id) { session ->
        require(value.length <= 32768)
        check(session.write(StorageRequest(StorageArea.Config, "variable", value)) is StorageResult.Value)
    }

    internal suspend fun supportsPixivBlocking(id: Identifier): Boolean = installedSources().any {
        ImportedRuleSources.id(it.definition) == id && pixivBlocking.accepts(it.definition)
    }

    internal suspend fun <T> withPixivPreferences(id: Identifier, action: (hnovel.network.SourceSession) -> T): T =
        withStoredSession(id) { session ->
            check(active[id]?.installed?.definition?.let(pixivBlocking::accepts) == true)
            action(session)
        }

    private suspend fun <T> withStoredSession(id: Identifier, action: (hnovel.network.SourceSession) -> T): T = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            accounts.withCurrent(id) { account ->
                val live = current.session?.takeIf { !it.closed && it.scope.accountGeneration == account.generation }
                if (live != null) action(live)
                else SourceBroker(File(directory, "runtime").toPath(), cipher = storageCipher).use { broker ->
                    // Same storage identity/cipher as execution, with no origins, browser or rule runner.
                    action(broker.open(SourceScope(id.namespace, id.id, current.installed.definition.profile,
                        account.generation), emptyList()))
                }
            }
        }
    }

    internal suspend fun rotateAccount(id: Identifier, expectedGeneration: Long? = null): RuleLoginTarget = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            check(current.rule != null) { "Source is disabled or unavailable" }
            if (expectedGeneration != null) check(accounts.current(id).generation == expectedGeneration) { "Login attempt is stale" }
            downloads?.revokeSourceTasks(id)
            accounts.begin(id)
            try { current.session?.clearAccount() } finally {
                current.broker?.close()
                active[id] = restoreBinding(current.installed, current).also { next ->
                            current.session?.let { next.session?.inheritCaches(it) }
                        }
            }
            val next = active.getValue(id)
            RuleLoginTarget(id, checkNotNull(next.registration).metadata.revision, next.registration.metadata.accountGeneration,
                checkNotNull(next.rule), checkNotNull(next.session))
        }
    }

    private fun save(installed: List<InstalledSource>, sourceGroups: List<SourceGroup> = groups) {
        check(installed.size <= ImportLimits().maxStoredEntries)
        directory.mkdirs()
        val bytes = Json.encodeToString(InstalledSnapshot.serializer(), InstalledSnapshot(installed, sourceGroups)).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_SNAPSHOT_BYTES)
        val output = committed.startWrite()
        try { output.write(bytes); committed.finishWrite(output) }
        catch (failure: Exception) { committed.failWrite(output); throw failure }
    }

    /** Stop process-owned registrations without changing the durable activation snapshot. */
    internal suspend fun stop() = lock.withLock {
        scope.cancel()
        active.values.forEach { it.registration?.unregister(); it.broker?.close(); it.session?.close() }
        active.clear()
    }

    @Serializable private data class SavedRevision(val definition: SourceDefinition, val origins: List<NetworkGrant>)
    @Serializable private data class InstalledSnapshot(val sources: List<InstalledSource>, val groups: List<SourceGroup> = emptyList())
    @Serializable private data class InstalledSource(val definition: SourceDefinition, val origins: List<NetworkGrant>,
        val previous: SavedRevision? = null, val preferences: SourcePreferences? = null,
        val bundledRepairs: Set<String> = emptySet()) {
        fun preferences() = preferences ?: SourcePreferences(definition.enabled, definition.enabledExplore)
    }
    private data class Binding(val installed: InstalledSource, val registration: SourceRegistration?, val broker: SourceBroker?,
        val session: hnovel.network.SourceSession? = null, val rule: RuleSource? = null)
    companion object {
        private const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
        fun id(definition: SourceDefinition) = Identifier("rules", definition.sourceId)
    }
}

data class InstalledRuleSource(val definition: SourceDefinition, val origins: List<NetworkGrant>, val previous: SourceDefinition?,
    val deniedOrigins: List<hnovel.network.OriginDenial> = emptyList(),
    val preferences: SourcePreferences = SourcePreferences(definition.enabled, definition.enabledExplore)) {
    val hasDiscovery: Boolean = kotlinx.serialization.json.Json.parseToJsonElement(definition.rawJson)
        .let { it as? kotlinx.serialization.json.JsonObject }?.get("exploreUrl")
        .let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.isNotBlank() == true
}

@Serializable data class SourcePreferences(val enabled: Boolean, val discoveryVisible: Boolean, val enabledSetByUser: Boolean = false,
    // Keep the old field readable; new membership writes use groupIds and clear groupId.
    @Serializable(with = indi.renakoni.nextvol.data.web.StoredSourceCategorySerializer::class)
    val category: SourceCategory? = null, private val groupId: String? = null,
    val groupIds: Set<String> = setOfNotNull(groupId))

internal data class RuleLoginTarget(val source: Identifier, val revision: String, val generation: Long,
    val rules: RuleSource, val session: hnovel.network.SourceSession)

internal data class RuleStoredSettings(val variable: String, val loginStatus: String?, val accountName: String? = null,
    val certificates: List<hnovel.network.CertificateExceptionSite> = emptyList())
