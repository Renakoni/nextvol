package hnovel.content

import hnovel.execution.LoginInfo
import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import hnovel.rules.*
import kotlinx.serialization.json.*

/** Login state belongs to one source ticket; the facade supplies the existing operation boundary. */
internal class RuleLoginWorkflow(binding: RuleSourceBinding,
    private val evaluation: (Boolean) -> RuleEvaluation,
    private val request: suspend (RuleEvaluation, String, String, BrowserOptions) -> BrokerResponse,
    private val checkStatus: (Int, String, Boolean) -> Unit,
    private val verification: (BrokerResult.Failure) -> SourceVerification?,
) {
    private val definition = binding.definition
    private val spec = binding.spec
    private val identity = binding.identity
    private val authority = binding.authority
    private val storage = binding.storage
    private val network = binding.network
    private var cachedLoginForm: LoginForm? = null

    suspend fun loginForm(): LoginForm = (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())

    private fun loginInfo(): String? {
        val stored = storage.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)) as? StorageResult.Value
            ?: throw SourceContentException(ContentError.Storage, "loginUi.values")
        return stored.value
    }

    private fun loginValues(): Map<String, String> = LoginInfo.stringFields(loginInfo()).orEmpty()
        .filter { (key, value) -> key.length <= 128 && value.jsonPrimitive.content.length <= 4096 }
        .mapValues { it.value.jsonPrimitive.content }

    private fun saveLoginValues(values: Map<String, String>) = authority.authorized(identity) {
        val original = loginInfo()
        val merged = LoginInfo.merge(original, values)
        if (merged != original)
            check(storage.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, merged)) is StorageResult.Value)
    }

    private fun loginContext(values: Map<String, String>, interactive: Boolean, longClick: Boolean = false): RuleEvaluation =
        evaluation(interactive).also {
        // Reuse the existing bounded interaction envelope; the login form owns this draft.
        // No exploration catalogue is evaluated and no Android objects cross the worker boundary.
        it.discovery = buildJsonObject {
            put("sessionId", "login"); put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            put("interactive", interactive); put("longClick", longClick); put("noBook", true)
        }
    }

    private fun loginPrelude(): String = if (spec.loginUrl.trim().startsWith("http://", true) ||
        spec.loginUrl.trim().startsWith("https://", true)) "" else scriptBody(spec.loginUrl)

    private suspend fun loginUiValue(context: RuleEvaluation, code: String, field: String): JsonElement {
        val value = context.script("result=JSON.parse(result);\n${loginPrelude()}\nJSON.stringify(eval(${JsonPrimitive(scriptBody(code))}));",
            RuleValue.Text(context.discovery!!.getValue("values").toString()), field).text()
        return Json.parseToJsonElement(value)
    }

    private suspend fun loadLoginForm(): LoginForm {
        val extended = definition.profile == EXTENSION_PROFILE
        val context = loginContext(loginValues().entries.take(128).associate { it.toPair() }, interactive = false)
        val raw = spec.loginUi.trim()
        val ui = if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
            if (!extended) throw SourceContentException(ContentError.InvalidRule, "loginUi")
            val result = loginUiValue(context, raw, "loginUi")
            if (result is JsonPrimitive && result.isString) result.content else result.toString()
        } else raw
        val form = LoginForm.parse(ui, if (spec.loginUi.isBlank()) spec.loginUrl else "", extended, spec.baseUrl)
            .withValues(loginValues())
        context.discovery = JsonObject(context.discovery!! + ("values" to JsonObject(form.values.mapValues { JsonPrimitive(it.value) })))
        val rendered = form.copy(fields = form.fields.mapIndexed { index, field ->
            if (field.viewName == null) field else {
                val value = loginUiValue(context, field.viewName, "loginUi[$index].viewName") as? JsonPrimitive
                    ?: throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                if (value == JsonNull || value.content.length > 256) throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                field.copy(label = value.content)
            }
        })
        if (context.discovery!!["saveSeconds"]?.let { it != JsonNull } == true)
            throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        return rendered.also { cachedLoginForm = it }
    }

    suspend fun login(values: Map<String, String>, action: String?, formId: String?,
        longClick: Boolean): LoginActionResult {
        if (formId != null && cachedLoginForm?.id != formId)
            throw SourceContentException(ContentError.Unavailable, "loginUi")
        val form = (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())
        // Trusted callers without a UI snapshot retain the unique-name API. UI submissions
        // always carry a form ID and can only address an opaque control ID from that form.
        val fieldAction = action?.let { id ->
            val field = form.fields.singleOrNull { if (formId == null) it.name == id else it.id == id }
                ?: throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
            if (!field.enabled) throw SourceContentException(ContentError.Unavailable, "loginUi.action")
            field.action ?: throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
        }
        form.validate(values)
        val submitted = form.values + values
        form.validate(submitted, allowAdditional = true)
        val info = JsonObject(submitted.mapValues { JsonPrimitive(it.value) }).toString()
        saveLoginValues(submitted)
        val context = loginContext(submitted, interactive = true, longClick = longClick && action != null)
        val messages = ArrayDeque<String>()
        context.onMessage = { message ->
            message.trim().take(4096).takeIf { it.isNotEmpty() }?.let {
                if (messages.size == 16) messages.removeFirst()
                messages.addLast(it)
            }
        }
        if (form.browserUrl != null && action == null) {
            val pending = if (spec.browserRead) (storage.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as? StorageResult.Value)?.value else null
            val response = network.execute(BrokerRequest("login", pending ?: form.browserUrl, headers = context.headers(),
                timeoutMillis = 60000, browser = BrowserOptions(interactive = true)),
                RequestCommitGuard { authority.authorized(identity, it) })
            when (response) {
                is BrokerResult.Failure -> throw SourceContentException(response.code.contentError(), "loginUrl", response.denial,
                    verification = verification(response))
                is BrokerResult.Success -> checkStatus(response.response.status, "loginUrl", response.response.kind == ResponseKind.BrowserDocument)
            }
            // Closing a website preserves its session; HTTP 200 alone does not verify a login.
            authority.authorized(identity) {
                check(storage.write(StorageRequest(StorageArea.Account, "login/status", "session")) is StorageResult.Value)
                if (pending != null) check(storage.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)) is StorageResult.Value)
            }
            return LoginActionResult()
        }
        val code = if (action == null) "if(typeof login!=='function')throw new Error('login missing');login();true;"
            else fieldAction!!
        if (code.startsWith("http://", true) || code.startsWith("https://", true)) {
            val response = request(context, code, "loginUi.action", BrowserOptions(interactive = true))
            checkStatus(response.status, "loginUi.action", response.kind == ResponseKind.BrowserDocument)
        } else context.script("result=JSON.parse(result);\n${loginPrelude()}\n${scriptBody(code)}", RuleValue.Text(info),
            if (action == null) "loginUrl" else "loginUi.action")
        val state = context.discovery!!
        if (state["saveSeconds"]?.let { it != JsonNull } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        val actions = (state["actions"] as? JsonArray).orEmpty()
        for (item in actions) {
            val command = item.jsonObject
            when (command.string("kind")) {
                "refresh" -> Unit
                "showBrowser" -> {
                    val args = command.getValue("args").jsonArray
                    if (args.size !in 1..4) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    fun arg(index: Int) = args.getOrNull(index)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    val config = arg(3)?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config?.keys?.any { it != "title" } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    val response = request(context, arg(0)!!, "loginUi.action.browser", BrowserOptions(interactive = true,
                        html = arg(1), script = arg(2).orEmpty(), title = config?.string("title").orEmpty()))
                    checkStatus(response.status, "loginUi.action.browser", response.kind == ResponseKind.BrowserDocument)
                }
                else -> throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
            }
        }
        val changed = state.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
        form.validate(changed, allowAdditional = true)
        if (changed != submitted) saveLoginValues(changed.filter { (key, value) -> submitted[key] != value })
        val refreshDiscovery = actions.any { it.jsonObject.string("kind") == "refresh" }
        if (refreshDiscovery) cachedLoginForm = null
        if (action == null) authority.authorized(identity) { check(storage.write(StorageRequest(StorageArea.Account, "login/status", "authenticated")) is StorageResult.Value) }
        return LoginActionResult(messages.toList(), refreshDiscovery)
    }
}
