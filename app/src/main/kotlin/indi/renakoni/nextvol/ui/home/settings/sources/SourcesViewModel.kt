package indi.renakoni.nextvol.ui.home.settings.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.LoginForm
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.data.web.zlibrary.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.utils.ofId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class SourceManagementState(val installed: List<InstalledRuleSource> = emptyList(),
    val catalog: List<CatalogSource> = emptyList(), val catalogPreview: Boolean = false,
    val registry: List<SourceListing> = emptyList(), val selected: Identifier? = null,
    val preview: ImportPreview? = null, val updateTarget: Identifier? = null,
    val previewOrigins: Map<Int, String> = emptyMap(),
    val busy: Boolean = false, val showProgress: Boolean = true, val message: Int? = null, val loginForm: LoginForm? = null,
    val configurationPanel: Boolean = false,
    val loginStatus: LoginStatus = LoginStatus.LoggedOut, val variable: String = "",
    val zLibrary: ZLibraryState = ZLibraryState(),
    val network: SourceNetworkState? = null, val storedSettingsAvailable: Boolean = false,
    val accountName: String? = null, val verifications: List<VerificationPrompt> = emptyList(),
    val groups: List<SourceGroup> = emptyList(), val groupRevision: Long = 0,
    val configurationError: SourceSaveError? = null, val variableError: SourceSaveError? = null,
    val editor: SourceEditorState? = null) {
    val ruleSettings = installed.find { ImportedRuleSources.id(it.definition) == selected }
        ?.let { RuleSettingsPresentation.read(it.definition) }
    val verification get() = verifications.firstOrNull { prompt ->
        !prompt.foreground && prompt.owner.source == selected && registry.any {
            it.metadata.id == prompt.owner.source && it.metadata.revision == prompt.owner.revision &&
                it.metadata.accountGeneration == prompt.owner.generation && it.status == SourceStatus.Ready
        }
    }
}

data class SourceNetworkState(val bypassVpn: Boolean = false, val limitation: Int? = null,
    val certificates: List<CertificateExceptionSite> = emptyList())

data class SourceSaveError(val message: Int, val detail: String? = null, val line: Int? = null, val column: Int? = null)
data class SourceEditorState(val installed: InstalledRuleSource, val rawJson: String,
    val error: SourceSaveError? = null, val saved: Boolean = false, val originalJson: String = rawJson,
    val pendingOrigins: List<String> = emptyList(), val jsonValid: Boolean = false) {
    val dirty get() = rawJson != originalJson
}

/** Screen state survives rotation; previews grant nothing and each explicit mutation has a single owner. */
@HiltViewModel
class SourcesViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val sources: ImportedRuleSources, private val updates: SourceRevisionUpdates,
    private val login: SourceLoginService, private val registry: WebSourceRegistry,
    private val zLibrary: ZLibrarySources,
    private val networkSettings: SourceNetworkSettings = SourceNetworkSettings(context, AndroidSourceNetworks(context)),
    private val verification: SourceVerificationCoordinator = SourceVerificationCoordinator(registry),
    private val catalog: SourceCatalog = SourceCatalog(context)) : ViewModel() {
    private val mutable = MutableStateFlow(SourceManagementState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var attempt: LoginAttempt? = null
    private var operationGeneration = 0
    private var openedFromDiscovery: Identifier? = null
    private var openedImportLink: String? = null
    private val foreground = ForegroundSourceRequest()
    fun setActive(value: Boolean, retainBrowser: Boolean = false) = foreground.setActive(value, retainBrowser)

    fun consumeSavedMessage() = consumeMessage(R.string.sources_saved)
    fun consumeMessage(message: Int) {
        mutable.update { if (it.message == message) it.copy(message = null) else it }
    }

    init {
        viewModelScope.launch { registry.sources.collect { list -> mutable.update { it.copy(registry = list) } } }
        viewModelScope.launch { verification.prompts.collect { prompts ->
            val previous = state.value.verifications
            mutable.update { it.copy(verifications = prompts) }
            val selected = state.value.selected
            // Verification can finish through the global notice while this settings page remains open.
            if (selected != null && previous.any { old -> old.owner.source == selected && prompts.none { it.id == old.id } })
                refreshStoredSettings(selected)
        } }
        viewModelScope.launch { zLibrary.state.collect { state -> mutable.update { it.copy(zLibrary = state) } } }
        refresh()
    }

    private fun launch(showProgress: Boolean = true, onFailure: ((Exception) -> Unit)? = null, block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val generation = ++operationGeneration
        mutable.update { it.copy(busy = true, showProgress = showProgress, message = null) }
        operation = viewModelScope.launch(foreground) {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (onFailure != null) onFailure(failure) else mutable.update { it.copy(message = when (failure) {
                is RevisionException -> when (failure.code) {
                    RevisionError.NoUpdateAddress -> R.string.sources_no_update_url
                    RevisionError.PermissionRequired -> R.string.sources_permission_denied
                    RevisionError.UpstreamNotAdapted -> R.string.sources_pixiv_update_unsupported
                    RevisionError.UpdateDownloadFailed -> R.string.sources_pixiv_update_failed
                    else -> R.string.sources_revision_failed
                }
                is SourceContentException -> sourceFailureMessage(failure)
                else -> R.string.sources_action_failed
            }) } }
            finally { if (generation == operationGeneration) mutable.update { it.copy(busy = false, showProgress = true) } }
        }
    }

    private suspend fun reload() {
        val installed = sources.installedSources()
        val groups = sources.sourceGroups()
        mutable.update { it.copy(installed = installed, groups = groups, catalog = catalog.entries) }
    }
    fun refresh() = launch { reload() }
    fun createGroup(name: String, members: Set<Identifier> = emptySet(),
        added: Set<String> = emptySet(), removed: Set<String> = emptySet()) = launch(showProgress = false) {
        sources.createGroup(name, members, added, removed); reload()
        mutable.update { it.copy(message = R.string.source_groups_saved, groupRevision = it.groupRevision + 1) }
    }
    fun renameGroup(id: String, name: String) = launch(showProgress = false) {
        sources.renameGroup(id, name); reload()
        mutable.update { it.copy(message = R.string.source_groups_saved, groupRevision = it.groupRevision + 1) }
    }
    fun deleteGroup(id: String) = launch(showProgress = false) {
        sources.deleteGroup(id); reload()
        mutable.update { it.copy(message = R.string.source_groups_saved, groupRevision = it.groupRevision + 1) }
    }
    fun updateGroups(members: Set<Identifier>, added: Set<String>, removed: Set<String>) = launch(showProgress = false) {
        sources.updateGroups(members, added, removed); reload()
        mutable.update { it.copy(message = R.string.source_groups_saved, groupRevision = it.groupRevision + 1) }
    }
    fun select(id: Identifier?) = launch(showProgress = false) { selectSource(id) }
    private suspend fun selectSource(id: Identifier?) {
        reload() // Includes the current session's redacted refusals, including background image loads.
        if (id == ZLibrarySources.ID) zLibrary.refresh()
        mutable.update { it.copy(selected = id, preview = null, updateTarget = null, configurationError = null, variableError = null, editor = null,
            loginStatus = if (it.selected == id) it.loginStatus else LoginStatus.LoggedOut,
            variable = if (it.selected == id) it.variable else "", storedSettingsAvailable = false,
            accountName = if (it.selected == id) it.accountName else null,
            network = id?.let(::networkState)) }
        if (id != null && mutable.value.installed.any { ImportedRuleSources.id(it.definition) == id }) {
            refreshStoredSettings(id)
        }
    }

    private suspend fun refreshStoredSettings(id: Identifier, form: LoginForm? = null) {
        if (state.value.selected != id) return
        try {
            val field = if (form != null) SourceLoginService.accountNameField(form) else state.value.ruleSettings?.accountNameField
            val saved = sources.storedSettings(id, field)
            mutable.update { if (it.selected == id) it.copy(loginStatus = SourceLoginService.savedStatus(saved.loginStatus),
                variable = saved.variable, storedSettingsAvailable = true, accountName = saved.accountName,
                network = it.network?.copy(certificates = saved.certificates)) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.update { if (it.selected == id) it.copy(storedSettingsAvailable = false, accountName = null,
                message = R.string.sources_action_failed) else it }
        }
    }

    fun openFromDiscovery(id: Identifier, signIn: Boolean) {
        if (openedFromDiscovery == id) return
        launch(showProgress = signIn) {
            selectSource(id)
            openedFromDiscovery = id
            if (signIn && SourceCapability.Login in registry.sources.value.find { it.metadata.id == id }.actionCapabilities()) {
                openLogin(id)
            }
        }
    }

    fun previewText(text: String, profile: String = AUTO_PROFILE) = launch { showPreview(sources.importer.preview(text, profile)) }
    fun previewCatalog(keys: Set<String>) = launch {
        val available = keys - state.value.installed.map { it.definition.importKey }.toSet()
        showPreview(sources.importer.preview(catalog.definitions(available), AUTO_PROFILE), catalogSelection = true)
    }
    fun openImportLink(url: String) {
        if (openedImportLink == url) return
        openedImportLink = url
        previewUrl(url)
    }
    fun previewFile(uri: Uri, profile: String = AUTO_PROFILE) = launch {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "source.json"
        } ?: "source.json"
        val preview = context.contentResolver.openInputStream(uri)?.use { sources.importer.previewStream(it, name, profile) }
            ?: error("Source file unavailable")
        showPreview(preview)
    }
    fun previewUrl(url: String, profile: String = AUTO_PROFILE) = launch {
        val address = sourceImportUrl(url) ?: error("Invalid book-source link")
        val root = File(context.cacheDir, "source-import-${UUID.randomUUID()}")
        try { SourceBroker(root.toPath(), limits = BrokerLimits(maxResponseBytes = ImportLimits().maxBytes)).use { broker ->
            val session = broker.open(SourceScope("import", UUID.randomUUID().toString(), profile), listOf(NetworkGrant(origin(address))))
            showPreview(sources.importer.previewUrl(address, session, profile))
        } } finally { root.deleteRecursively() }
    }
    private fun showPreview(preview: ImportPreview, target: Identifier? = null, catalogSelection: Boolean = false) {
        mutable.update { it.copy(preview = preview, updateTarget = target, catalogPreview = catalogSelection,
            previewOrigins = candidateOrigins(preview), message = if (preview.issues.any { issue -> issue.code != ImportCode.UnsupportedType }) R.string.sources_import_invalid else null) }
    }
    private fun candidateOrigins(preview: ImportPreview) = preview.candidates.associate {
        it.index to SourceOriginCandidates.discover(Json.parseToJsonElement(it.rawJson).jsonObject)
            .map { candidate -> candidate.origin }.distinct().joinToString("\n")
    }
    fun dismissPreview() { if (!state.value.busy) mutable.update { it.copy(preview = null, previewOrigins = emptyMap(), updateTarget = null) } }

    fun commit(selected: Set<Int>, permissions: Map<Int, String>, allowIdentityChange: Boolean) = launch {
        commitSelection(checkNotNull(state.value.preview), selected, permissions, allowIdentityChange, state.value.updateTarget)
    }
    private suspend fun commitSelection(preview: ImportPreview, selected: Set<Int>, permissions: Map<Int, String>,
        allowIdentityChange: Boolean, updateTarget: Identifier? = null) {
        val snapshot = mutable.value
        require(selected.isNotEmpty())
        val candidates = preview.candidates.filter { it.index in selected }
        require(candidates.size == selected.size)
        val target = updateTarget?.let { id -> snapshot.installed.single { ImportedRuleSources.id(it.definition) == id } }
        if (target != null) require(candidates.size == 1)
        val grants = candidates.associate { it.index to grants(permissions.getValue(it.index)) }
        val selections = candidates.map { candidate ->
            val decision = if (target != null && target.definition.importKey != candidate.importKey) {
                require(allowIdentityChange); ImportDecision.MapIdentity(target.definition.reference())
            } else candidate.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add
            ImportSelection(candidate.index, decision)
        }
        val committed = sources.importer.commit(preview, selections)
        var failed = committed.error != null
        try {
            val definitions = sources.definitions.list().associateBy { it.reference() }
            val installed = snapshot.installed.associateBy { ImportedRuleSources.id(it.definition) }
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
                    groupByCatalog = snapshot.catalogPreview).size != additions.size) failed = true
        } finally {
            // Import definitions are already committed. Retrying requires a fresh preview of that state.
            withContext(NonCancellable) {
                reload()
                mutable.update { it.copy(preview = null, previewOrigins = emptyMap(), updateTarget = null) }
            }
        }
        mutable.update { it.copy(message = if (failed) R.string.sources_import_partial else R.string.sources_saved) }
    }
    fun checkUpdate(id: Identifier) = launch {
        showUpdate(updates.check(id), id)
    }
    fun canCheckUpdate(definition: SourceDefinition): Boolean = updates.canCheckUpdate(definition)
    private fun showUpdate(checked: RevisionCheck, id: Identifier) {
        if (checked.unchanged) mutable.update { it.copy(message = R.string.sources_up_to_date) }
        else showPreview(checked.preview, id)
    }
    fun setEnabled(id: Identifier, enabled: Boolean) = launch {
        val hasDiscovery = state.value.installed.firstOrNull { ImportedRuleSources.id(it.definition) == id }?.hasDiscovery == true
        // Enabling a source is the single user action that makes its catalogue visible.
        // Sources without exploreUrl remain search-only.
        sources.setPreferences(id, enabled = enabled, discoveryVisible = enabled && hasDiscovery)
        if (state.value.selected == id) selectSource(id) else reload()
    }
    fun setDiscoveryVisible(id: Identifier, visible: Boolean) = launch {
        sources.setPreferences(id, discoveryVisible = visible); selectSource(id)
    }
    fun setZLibraryEnabled(enabled: Boolean) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(enabled = enabled))
    }
    private fun networkState(id: Identifier): SourceNetworkState {
        val installed = state.value.installed.find { ImportedRuleSources.id(it.definition) == id }
        val listing = state.value.registry.find { it.metadata.id == id }
        val limitation = when {
            installed != null -> if (RuleSettingsPresentation.read(installed.definition).nativeBrowser &&
                !AndroidSourceBrowser.supportsVpnBypass(context)) R.string.sources_network_native else null
            id == ZLibrarySources.ID -> null
            id == "Wenku8".ofId() && listing?.metadata?.builtIn == true -> null
            else -> R.string.sources_network_unsupported
        }
        return SourceNetworkState(networkSettings.mode(id) == SourceNetworkMode.BypassVpn, limitation,
            state.value.network?.certificates.orEmpty().takeIf { state.value.selected == id }.orEmpty())
    }
    fun revokeCertificate(origin: String) = launch {
        val id = checkNotNull(state.value.selected)
        sources.revokeCertificate(id, origin)
        selectSource(id)
        mutable.update { it.copy(message = R.string.source_certificate_revoked) }
    }
    fun setBypassVpn(enabled: Boolean) = launch(showProgress = false) {
        val id = checkNotNull(state.value.selected)
        check(!enabled || networkState(id).limitation == null)
        networkSettings.setBypassVpn(id, enabled)
        // No source registration/revision or account change; running chains keep their route.
        mutable.update { it.copy(network = networkState(id), message = R.string.sources_saved) }
    }
    fun saveZLibrary(origin: String, permissions: String) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(origin = origin,
            origins = permissions.lines().filter(String::isNotBlank)))
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun clearConfigurationError() { mutable.update { it.copy(configurationError = null) } }
    fun clearVariableError() { mutable.update { it.copy(variableError = null) } }
    fun saveVariable(id: Identifier, variable: String) = launch(showProgress = false, onFailure = { failure ->
        mutable.update { it.copy(variableError = saveError(failure)) }
    }) {
        clearVariableError()
        if (variable.length > 32768) {
            mutable.update { it.copy(variableError = SourceSaveError(R.string.sources_variable_too_long)) }
            return@launch
        }
        sources.saveVariable(id, variable)
        mutable.update { it.copy(variable = if (it.selected == id) variable else it.variable, message = R.string.sources_saved) }
    }
    fun savePermissions(id: Identifier, permissions: String) = launch(onFailure = { failure ->
        mutable.update { it.copy(configurationError = saveError(failure)) }
    }) {
        clearConfigurationError()
        val installed = state.value.installed.single { ImportedRuleSources.id(it.definition) == id }
        val approved = editedGrants(permissions, installed)
        updates.updatePermissions(id, approved)
        selectSource(id)
        mutable.update { it.copy(message = R.string.sources_saved) }
    }

    fun beginEditor(id: Identifier) = launch(showProgress = false) {
        val installed = sources.installedSources().single { ImportedRuleSources.id(it.definition) == id }
        mutable.update { it.copy(editor = SourceEditorState(installed, SourceDefinitionEdits.format(installed.definition.rawJson)), configurationError = null) }
    }
    fun closeEditor() { if (!state.value.busy) mutable.update { it.copy(editor = null) } }
    fun editDraft(rawJson: String) {
        if (!state.value.busy) mutable.update { state -> state.copy(editor = state.editor?.copy(rawJson = rawJson,
            pendingOrigins = emptyList(), error = null, saved = false, jsonValid = false)) }
    }
    fun validateEditor() = launch(showProgress = false, onFailure = { failure ->
        mutable.update { it.copy(editor = it.editor?.copy(error = saveError(failure), jsonValid = false, saved = false)) }
    }) {
        val draft = checkNotNull(state.value.editor)
        SourceDefinitionEdits.validateJson(draft.rawJson)
        mutable.update { it.copy(editor = draft.copy(error = null, jsonValid = true, saved = false)) }
    }
    fun formatEditor() = launch(showProgress = false, onFailure = { failure ->
        mutable.update { it.copy(editor = it.editor?.copy(error = saveError(failure), jsonValid = false, saved = false)) }
    }) {
        val draft = checkNotNull(state.value.editor)
        mutable.update { it.copy(editor = draft.copy(rawJson = SourceDefinitionEdits.format(draft.rawJson),
            pendingOrigins = emptyList(), error = null, saved = false, jsonValid = true)) }
    }
    fun dismissEditorOrigins() { if (!state.value.busy) mutable.update { it.copy(editor = it.editor?.copy(pendingOrigins = emptyList())) } }
    fun saveEditor() = persistEditor(false)
    fun approveEditorOrigins() = persistEditor(true)
    private fun persistEditor(approve: Boolean) = launch(showProgress = false, onFailure = { failure ->
        mutable.update { it.copy(editor = it.editor?.copy(error = saveError(failure), pendingOrigins = emptyList(), saved = false)) }
    }) {
        val draft = checkNotNull(state.value.editor)
        if (approve && draft.pendingOrigins.isEmpty()) return@launch
        val next = SourceDefinitionEdits.prepare(draft.installed.definition, draft.rawJson)
        val existing = SourceOriginCandidates.discover(Json.parseToJsonElement(draft.installed.definition.rawJson).jsonObject).map { it.origin }.toSet()
        val granted = draft.installed.origins.mapNotNull { sourcePermissionOrigin(it.origin) }.toSet()
        val introduced = SourceOriginCandidates.discover(Json.parseToJsonElement(next.rawJson).jsonObject)
            .map { it.origin }.distinct().filter { it !in existing && it !in granted }
        if (introduced.isNotEmpty() && (!approve || draft.pendingOrigins != introduced)) {
            mutable.update { it.copy(editor = draft.copy(pendingOrigins = introduced, error = null, saved = false)) }
            return@launch
        }
        mutable.update { it.copy(editor = draft.copy(error = null, pendingOrigins = emptyList(), saved = false)) }
        val id = ImportedRuleSources.id(draft.installed.definition)
        updates.edit(id, draft.installed, draft.rawJson, draft.installed.origins + introduced.map { NetworkGrant(it) })
        reload()
        val installed = state.value.installed.single { ImportedRuleSources.id(it.definition) == id }
        mutable.update { it.copy(editor = SourceEditorState(installed, SourceDefinitionEdits.format(installed.definition.rawJson), saved = true)) }
    }

    private fun editedGrants(text: String, installed: InstalledRuleSource): List<NetworkGrant> {
        val invalid = invalidPermissionLines(text)
        if (invalid.isNotEmpty()) throw InvalidPermissions(invalid)
        return grants(text).map { grant -> installed.origins.firstOrNull { sourcePermissionOrigin(it.origin) == grant.origin } ?: grant }
    }
    private class InvalidPermissions(val lines: List<Int>) : Exception()
    private fun saveError(failure: Exception): SourceSaveError = when (failure) {
        is InvalidPermissions -> SourceSaveError(R.string.sources_invalid_permission_lines, failure.lines.joinToString(", "))
        is SourceEditException -> when (failure.code) {
            ImportCode.InvalidJson -> SourceSaveError(R.string.sources_editor_syntax_error, line = failure.line, column = failure.column)
            ImportCode.DuplicateField -> SourceSaveError(R.string.sources_editor_duplicate_field, line = failure.line, column = failure.column)
            ImportCode.InvalidShape -> SourceSaveError(R.string.sources_editor_object_required)
            ImportCode.TooDeep -> SourceSaveError(R.string.sources_editor_too_deep, line = failure.line, column = failure.column)
            ImportCode.TooLarge -> SourceSaveError(R.string.sources_editor_too_large)
            else -> SourceSaveError(R.string.sources_editor_invalid,
                failure.field?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,120}")) } ?: "JSON")
        }
        is RevisionException -> when (failure.code) {
            RevisionError.PermissionRequired -> failure.requiredOrigin?.let { SourceSaveError(R.string.sources_missing_origin, it) }
                ?: SourceSaveError(R.string.sources_permission_denied)
            RevisionError.Stale -> SourceSaveError(R.string.sources_editor_stale)
            RevisionError.InitializationFailed -> SourceSaveError(R.string.sources_editor_initialization_failed)
            RevisionError.UpstreamNotAdapted -> SourceSaveError(R.string.sources_pixiv_update_unsupported)
            else -> SourceSaveError(R.string.sources_revision_failed)
        }
        else -> SourceSaveError(R.string.sources_action_failed)
    }
    fun remove(id: Identifier) = launch {
        sources.remove(id); reload()
        mutable.update { it.copy(selected = null, message = R.string.sources_removed) }
    }
    fun beginLogin(id: Identifier) = launch { openLogin(id) }
    fun beginConfiguration(id: Identifier) = launch {
        mutable.update { it.copy(configurationPanel = true, loginForm = null) }
        try { openLogin(id, configuration = true) }
        finally { if (state.value.loginForm == null) mutable.update { it.copy(configurationPanel = false) } }
    }
    fun relogin(id: Identifier) = launch { openLogin(id, LoginIntent.Relogin) }
    private suspend fun openLogin(id: Identifier, intent: LoginIntent = LoginIntent.Panel, configuration: Boolean = false) {
        val definition = sources.installedSources().single { ImportedRuleSources.id(it.definition) == id }.definition
        val declaration = RuleSettingsPresentation.read(definition)
        if (!declaration.loginDeclared || declaration.loginErrorField != null)
            throw SourceContentException(hnovel.content.ContentError.InvalidRule, declaration.loginErrorField ?: "loginUi")
        check(registry.resolve(id) is SourceResolution.Ready) { "Source is not initialized" }
        // Retire the previous panel even when the account itself is retained.
        val active = withContext(NonCancellable) {
            attempt?.let { login.cancel(it) }
            login.begin(id, intent).also { attempt = it }
        }
        var form: LoginForm? = null
        try {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(loginForm = null, configurationPanel = configuration) }
            val loaded = login.form(active).also { form = it }
            currentCoroutineContext().ensureActive()
            val action = SourceLoginService.directLoginAction(loaded).takeUnless { configuration }
            if (!configuration && (loaded.browserUrl != null && loaded.fields.isEmpty() || action != null)) {
                login.submit(active, loaded.values, action, loaded.id)
                login.cancel(active)
                attempt = null
                mutable.update { it.copy(loginForm = null) }
            } else mutable.update { it.copy(loginForm = loaded) }
        } catch (failure: Exception) {
            withContext(NonCancellable) { login.cancel(active) }
            if (attempt === active) attempt = null
            throw failure
        } finally {
            withContext(NonCancellable) { refreshStoredSettings(id, form) }
        }
    }
    suspend fun configurationBlockManager(action: String?, formId: String): Identifier? {
        val current = state.value
        val active = attempt ?: return null
        val form = current.loginForm ?: return null
        if (current.busy || !current.configurationPanel || form.id != formId) return null
        val field = form.fields.singleOrNull { it.id == action && it.enabled && it.type == "button" } ?: return null
        if (field.action?.trim()?.removeSuffix(";")?.trim() != "pixivBlockManager()") return null
        val supported = withContext(Dispatchers.IO) { sources.supportsPixivBlocking(active.source) }
        return active.source.takeIf { supported && attempt === active && state.value.loginForm?.id == formId && !state.value.busy }
    }

    fun configurationLink(action: String?, formId: String): String? {
        val current = state.value
        val active = attempt ?: return null
        val form = current.loginForm ?: return null
        if (current.busy || !current.configurationPanel || form.id != formId) return null
        val field = form.fields.singleOrNull { it.id == action && it.enabled && it.type == "button" } ?: return null
        if (field.action?.trim()?.removeSuffix(";")?.trim() != "startGithubReadme()") return null
        val definition = current.installed.singleOrNull { ImportedRuleSources.id(it.definition) == active.source }?.definition
            ?: return null
        return "https://pixivsource.pages.dev/Pixiv".takeIf { updates.manages(definition) }
    }

    fun submitLogin(values: Map<String, String>, action: String? = null, formId: String? = null, longClick: Boolean = false) = launch {
        val active = checkNotNull(attempt)
        val submittedForm = state.value.loginForm
        try {
            val update = submittedForm?.takeIf { it.id == formId }?.fields?.singleOrNull {
                it.id == action && it.enabled && it.type == "button" &&
                    it.action?.trim()?.removeSuffix(";")?.trim() == "updateSource()"
            }
            val definition = state.value.installed.singleOrNull { ImportedRuleSources.id(it.definition) == active.source }?.definition
            if (state.value.configurationPanel && update != null && definition != null && updates.manages(definition)) {
                val checked = login.withAttempt(active) { updates.check(active.source, expected = definition.reference()) }
                if (checked.unchanged) {
                    mutable.update { it.copy(message = R.string.sources_up_to_date) }
                    return@launch
                }
                withContext(NonCancellable) { login.cancel(active) }
                attempt = null
                mutable.update { it.copy(loginForm = null, configurationPanel = false) }
                showUpdate(checked, active.source)
                return@launch
            }
            login.submit(active, values, action, formId, longClick)
            val form = if (action == null) null else login.form(active)
            if (action == null) { login.cancel(active); attempt = null }
            mutable.update { it.copy(loginForm = form, configurationPanel = it.configurationPanel && form != null) }
        } finally {
            withContext(NonCancellable) { refreshStoredSettings(active.source, submittedForm) }
        }
    }
    fun logout(id: Identifier) = launch {
        login.logout(id)
        refreshStoredSettings(id)
    }
    fun verifyPending() = launch {
        val prompt = state.value.verification ?: return@launch
        // The coordinator owns the original request and account. Opening verification is not a new login attempt.
        try { verification.verifyBackground(prompt.id) }
        finally { withContext(NonCancellable) { refreshStoredSettings(prompt.owner.source) } }
    }
    fun cancelLogin() {
        val pending = operation
        pending?.cancel()
        val generation = ++operationGeneration
        mutable.update { it.copy(loginForm = null, configurationPanel = false,
            busy = true, showProgress = true) }
        val active = attempt; attempt = null
        viewModelScope.launch {
            withContext(NonCancellable) {
                pending?.join()
                if (active != null) {
                    login.cancel(active)
                    if (generation == operationGeneration) refreshStoredSettings(active.source)
                }
            }
            if (generation == operationGeneration) mutable.update { it.copy(loginForm = null, configurationPanel = false,
                busy = false) }
        }
    }
    fun cancel() { operation?.cancel() }
    override fun onCleared() {
        foreground.setActive(false)
        val active = attempt
        if (active != null) CoroutineScope(Dispatchers.IO).launch { login.cancel(active) }
    }
    companion object {
        fun origin(url: String): String = requireNotNull(sourcePermissionOrigin(url))
        fun invalidPermissionLines(text: String): List<Int> = text.lines().mapIndexedNotNull { index, line ->
            (index + 1).takeIf { line.isNotBlank() && sourcePermissionOrigin(line) == null }
        }
        fun grants(text: String): List<NetworkGrant> = text.lines().filter { it.isNotBlank() }.map { NetworkGrant(origin(it)) }
            .distinctBy { it.origin }.also { require(it.size <= 32) }
    }
}
