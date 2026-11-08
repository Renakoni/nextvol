package indi.renakoni.nextvol.ui.home.settings.sources

import hnovel.content.LoginForm
import hnovel.content.SourceContentException
import hnovel.imports.EXTENSION_PROFILE
import hnovel.imports.SourceDefinition
import indi.renakoni.nextvol.data.web.rules.SourceLoginService
import kotlinx.serialization.json.*

/** Only declaration data for the fixed settings sections. Never initializes rules or evaluates JS. */
data class RuleSettingsPresentation(val loginDeclared: Boolean = false, val loginErrorField: String? = null,
    val variableDescription: String = "", val nativeBrowser: Boolean = false, val accountNameField: String? = null,
    val configurationDeclared: Boolean = false) {
    companion object {
        fun read(definition: SourceDefinition): RuleSettingsPresentation {
            val raw = runCatching { Json.parseToJsonElement(definition.rawJson) as? JsonObject }.getOrNull()
                ?: return RuleSettingsPresentation(loginErrorField = "definition")
            // Match rule-definition string coercion; structured loginUi data is parsed as data too.
            fun text(key: String): String = when (val value = raw[key]) {
                null, JsonNull -> ""
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
            val url = text("loginUrl")
            val ui = text("loginUi").trim()
            val declared = url.isNotBlank() || ui.isNotBlank()
            val base = RuleSettingsPresentation(declared, variableDescription = text("variableComment"),
                nativeBrowser = (raw["browserRead"] as? JsonPrimitive)?.booleanOrNull == true)
            if (!declared) return base // loginCheckJs alone does not create a login entry.
            val extended = definition.profile == EXTENSION_PROFILE
            if (ui.startsWith("@js:", true) || ui.startsWith("<js>", true))
                return if (extended) base.copy(configurationDeclared = true) else base.copy(loginErrorField = "loginUi")
            return try {
                val form = LoginForm.parse(ui, url, extended, text("bookSourceUrl"))
                base.copy(loginDeclared = url.isNotBlank() || form.fields.isNotEmpty(),
                    accountNameField = SourceLoginService.accountNameField(form),
                    configurationDeclared = form.fields.any { it.action != null })
            } catch (failure: SourceContentException) { base.copy(loginErrorField = failure.field) }
              catch (_: IllegalArgumentException) { base.copy(loginErrorField = "loginUi") }
        }
    }
}
