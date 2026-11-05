package indi.renakoni.nextvol.tts

import hnovel.speech.SpeechPackagePreview
import hnovel.speech.SpeechPackageValue
import hnovel.speech.isOnlineSpeechEngine

/** A static mapping of MultiTTS YAML; rules are evaluated only by the normal reader pipeline. */
data class SpeechConfigurationImport(
    val replacements: List<SpeechReplacementRule>? = null,
    val roles: List<SpeechRoleRule>? = null,
    val voiceItems: List<SpeechVoiceItem>? = null,
    val unsupported: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasChanges get() = replacements != null || roles != null || voiceItems != null
    val unresolvedVoices get() = (roles.orEmpty().mapNotNull { it.speaker } + voiceItems.orEmpty().map { it.value })
        .filter { it.externalId != null && it.httpSource == null && it.offlineVoice == null }.distinctBy { it.key }

    fun conflicts(current: SpeechSettings): List<String> = buildList {
        val replacementsByName = current.replacements.map { it.group to it.name }.toSet()
        replacements.orEmpty().filter { (it.group to it.name) in replacementsByName }
            .forEach { add("replaces.yaml: ${it.group} / ${it.name}") }
        val rolesByName = current.roleRules.map { it.group to it.name }.toSet()
        roles.orEmpty().filter { (it.group to it.name) in rolesByName }
            .forEach { add("roles.yaml: ${it.group} / ${it.name}") }
        val voicesByName = current.voiceItems.map { it.group to it.name }.toSet()
        voiceItems.orEmpty().filter { (it.group to it.name) in voicesByName }
            .forEach { add("voice_pool.yaml: ${it.group} / ${it.name}") }
    }

    fun applyTo(current: SpeechSettings, replace: Boolean): SpeechSettings {
        val replacementsByName = current.replacements.associateBy { it.group to it.name }
        val incomingReplacements = if (replace) configurationIds(replacements, current.replacements,
            { it.group to it.name }, { it.id }, { item, id -> item.copy(id = id) }) else replacements?.map { item ->
            item.copy(id = replacementsByName[item.group to item.name]?.id ?: item.id)
        }
        val incomingRoles = configurationIds(roles, current.roleRules,
            { it.group to it.name }, { it.id }, { item, id -> item.copy(id = id,
                person = item.person ?: current.roleRules.firstOrNull {
                    it.id == id && it.pattern == item.pattern && it.mode == item.mode
                }?.person) })
        val incomingVoices = configurationIds(voiceItems, current.voiceItems,
            { it.group to it.name }, { it.id }, { item, id -> item.copy(id = id) })
        // N6.a.e replaces rules in place; O6.d.i / O6.j.f remove conflicts then append imported rows.
        val mergedReplacements = incomingReplacements?.let { incoming ->
            if (replace) incoming else current.replacements.toMutableList().apply {
                val indices = mapIndexed { index, item -> (item.group to item.name) to index }.toMap()
                incoming.forEach { item ->
                    val index = indices[item.group to item.name]
                    if (index == null) add(item) else set(index, item)
                }
            }
        }
        val roleNames = incomingRoles.orEmpty().map { it.group to it.name }.toSet()
        val voiceNames = incomingVoices.orEmpty().map { it.group to it.name }.toSet()
        return current.copy(
            replacements = mergedReplacements ?: current.replacements,
            roleRules = incomingRoles?.let { incoming ->
                (if (replace) emptyList() else current.roleRules.filterNot { (it.group to it.name) in roleNames }) + incoming
            } ?: current.roleRules,
            voiceItems = incomingVoices?.let { incoming ->
                (if (replace) emptyList() else current.voiceItems.filterNot { (it.group to it.name) in voiceNames }) + incoming
            } ?: current.voiceItems,
        )
    }
}

/** MultiTTS rows have no stable IDs; retain NextVol editor identities without collapsing duplicate rows. */
private fun <T> configurationIds(incoming: List<T>?, current: List<T>, key: (T) -> Pair<String, String>,
    id: (T) -> String, copy: (T, String) -> T): List<T>? {
    if (incoming == null) return null
    val ownIds = incoming.map(id).toSet()
    val remaining = current.groupBy(key).mapValues { (_, rows) -> rows.map(id).toMutableList() }
    return incoming.map { item ->
        val own = id(item)
        val previous = remaining[key(item)]
        val reused = if (previous?.contains(own) == true) own else previous?.firstOrNull { it !in ownIds } ?: own
        previous?.remove(reused)
        copy(item, reused)
    }
}

fun previewSpeechConfiguration(preview: SpeechPackagePreview, voices: Map<String, SpeechVoiceRef>, defaultGroup: String): SpeechConfigurationImport {
    preview.error?.let { return SpeechConfigurationImport(error = it.name) }
    val unsupported = linkedSetOf<String>()
    var replacements: List<SpeechReplacementRule>? = null
    var roles: List<SpeechRoleRule>? = null
    var voiceItems: List<SpeechVoiceItem>? = null
    fun voice(id: String, name: String): SpeechVoiceRef? = when (id) {
        "" -> null
        SPEECH_POOL_MALE, SPEECH_POOL_FEMALE -> SpeechVoiceRef(voice = id, label = name)
        else -> voices[id]?.copy(externalId = id) ?: SpeechVoiceRef(externalId = id, label = name.ifEmpty { id })
    }
    fun unknown(fields: Map<String, SpeechPackageValue>, supported: Set<String>, path: String) {
        fields.keys.filterNot { it in supported }.forEach { unsupported += "$path.$it" }
    }
    return try {
        // HTTP definitions retain their complete raw data; offline definitions map the original Speaker fields.
        val speakerFields = setOf("id", "group", "code", "name", "avatar", "desc", "extendUI", "gender",
            "locale", "note", "param", "pitch", "sampleRate", "speed", "type", "volume")
        val definitions = preview.documents.flatMap { document ->
            val root = document.value as? SpeechPackageValue.Mapping
            (root?.values?.get("engines") as? SpeechPackageValue.Sequence)?.values.orEmpty()
        }.filterIsInstance<SpeechPackageValue.Mapping>().associateBy { (it.values["code"] as? SpeechPackageValue.Scalar)?.value }
        for (engine in preview.engines.filterNot { isOnlineSpeechEngine(it.code, it.type) }) {
            definitions[engine.code]?.let { unknown(it.values, setOf("code", "name", "type", "param", "note"), "engines.yaml.${engine.code}") }
            engine.voices.forEachIndexed { index, voice ->
                unknown(voice.raw.values, speakerFields, "config.yaml.${engine.code}[$index]")
            }
        }
        for (document in preview.documents) {
            val filename = document.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
            // These files belong to the existing engine/voice import, never to application preferences.
            if (filename == "config" || filename == "engines") continue
            val root = configurationMap(document.value, document.path)
            if (filename == "voice_pool") {
                val incoming = root.flatMap { (group, rows) ->
                    configurationRows(rows, "${document.path}.$group").mapIndexed { index, value ->
                        val path = "${document.path}.$group[$index]"
                        val row = configurationMap(value, path)
                        unknown(row, setOf("group", "name", "value", "activate", "id"), path)
                        val name = row.configurationText("name", path)
                        val id = row.configurationText("value", path)
                        SpeechVoiceItem(group = configurationPoolGroup(group),
                            value = voice(id, name) ?: SpeechVoiceRef(externalId = "", label = name),
                            name = name, activate = row.configurationBoolean("activate", path, false))
                    }
                }
                voiceItems = voiceItems.orEmpty() + incoming
                continue
            }
            val hasReplacements = filename == "replaces" || "replaces" in root
            val hasRoles = filename == "roles" || "roles" in root
            if (hasReplacements) {
                val incoming = configurationRows(root["replaces"], "${document.path}.replaces").mapIndexed { index, value ->
                    val path = "${document.path}.replaces[$index]"
                    val row = configurationMap(value, path)
                    unknown(row, setOf("group", "name", "source", "target", "regex", "activate"), path)
                    SpeechReplacementRule(group = row.configurationText("group", path).ifEmpty { defaultGroup }, name = row.configurationText("name", path),
                        source = row.configurationText("source", path), target = row.configurationText("target", path),
                        regex = row.configurationBoolean("regex", path, false), activate = row.configurationBoolean("activate", path, true))
                }
                replacements = replacements.orEmpty() + incoming
            }
            if (hasRoles) {
                val incoming = configurationRows(root["roles"], "${document.path}.roles").mapIndexed { index, value ->
                    val path = "${document.path}.roles[$index]"
                    val row = configurationMap(value, path)
                    unknown(row, setOf("group", "name", "regex", "activate", "mode", "isAbstract", "matchBefore", "matchAfter",
                        "speakerId", "speakerName", "message"), path)
                    if (row.configurationText("message", path).isNotEmpty()) unsupported += "$path.message"
                    val mode = row.configurationText("mode", path).let { text ->
                        if (text.isEmpty()) 2 else text.toIntOrNull() ?: throw ConfigurationFieldFailure("$path.mode")
                    }
                    SpeechRoleRule(group = row.configurationText("group", path).ifEmpty { defaultGroup }, name = row.configurationText("name", path),
                        regex = row.configurationText("regex", path), activate = row.configurationBoolean("activate", path, false),
                        mode = mode, isAbstract = row.configurationBoolean("isAbstract", path, false),
                        matchBefore = row.configurationBoolean("matchBefore", path, false),
                        matchAfter = row.configurationBoolean("matchAfter", path, false),
                        speaker = voice(row.configurationText("speakerId", path), row.configurationText("speakerName", path)))
                }
                roles = roles.orEmpty() + incoming
            }
            unknown(root, setOf("replaces", "roles"), document.path)
        }
        preview.resources.filter { it.path.substringAfterLast('/') == "hanlp.properties" }
            .forEach { unsupported += it.path }
        SpeechConfigurationImport(replacements, roles, voiceItems, unsupported.toList())
    } catch (failure: ConfigurationFieldFailure) {
        SpeechConfigurationImport(unsupported = unsupported.toList(), error = failure.path)
    }
}

private class ConfigurationFieldFailure(val path: String) : Exception()

private fun configurationMap(value: SpeechPackageValue, path: String) =
    (value as? SpeechPackageValue.Mapping)?.values ?: throw ConfigurationFieldFailure(path)

private fun configurationRows(value: SpeechPackageValue?, path: String): List<SpeechPackageValue> = when {
    value is SpeechPackageValue.Sequence -> value.values
    value == null || value is SpeechPackageValue.Scalar && value.configurationText(path) == null -> emptyList()
    else -> throw ConfigurationFieldFailure(path)
}

private fun SpeechPackageValue.configurationText(path: String): String? {
    val scalar = this as? SpeechPackageValue.Scalar ?: throw ConfigurationFieldFailure(path)
    return if (scalar.tag == "tag:yaml.org,2002:null" || scalar.tag == null && scalar.plain &&
        scalar.value.lowercase() in setOf("null", "~", "")) null else scalar.value
}

private fun Map<String, SpeechPackageValue>.configurationText(key: String, path: String) =
    get(key)?.configurationText("$path.$key").orEmpty()

private fun Map<String, SpeechPackageValue>.configurationBoolean(key: String, path: String, default: Boolean): Boolean =
    when (get(key)?.configurationText("$path.$key")?.lowercase()) {
        null -> default
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> throw ConfigurationFieldFailure("$path.$key")
    }

// Exact msg_mismatch_* labels from MultiTTS 1.8.3 resources; its YAML persists localized pool names.
internal fun configurationPoolGroup(group: String): String = when (group) {
    "Desajuste hombre", "Discord Lalaki", "Discordance Homme", "Discordanza Maschio", "El género masculino no coincide",
    "Fehlanpassung Mann", "Felmatchning Man", "Hombre no coincidente", "Ketidakcocokan Laki-laki", "Male Mismatch",
    "Mismatch Male", "Niedopasowanie Mężczyzna", "O sexo masculino não corresponde", "Uyumsuzluk Erkek",
    "Verkeerde match Man", "nam Không Phù Hợp", "Інші чоловічі", "Другие мужские", "ذَكَر غير متطابق", "مماثل مرد",
    "نێری ناتەبا", "न जुळणारा पुरुष", "बेमेल पुरुष", "অমিল পুরুষ", "ਬੇਮੇਲ ਮਰਦ", "મેળ ખાતો પુરૂષ",
    "ଅସନ୍ତୁଷ୍ଟ ପୁରୁଷ |", "பொருந்தாத ஆண்", "పురుషుడు సరిపోలలేదు", "ಹೊಂದಿಕೆಯಾಗದ ಪುರುಷ",
    "പൊരുത്തക്കേട് പുരുഷൻ", "ผู้ชายอื่นๆ", "ប្រុសមិនស៊ីគ្នា។", "失配男" -> SPEECH_POOL_MALE
    "Desajuste mujer", "Discord Woman", "Discordance Femme", "Discordanza Femmina", "El género femenino no coincide",
    "Fehlanpassung Frau", "Felmatchning Kvinna", "Jinê mêtingeh", "Ketidakcocokan Wanita", "Mismatch Female",
    "Mujer no coincidente", "Niedopasowanie Kobieta", "O sexo feminino não corresponde", "Uyumsuzluk Kadın",
    "Verkeerde match Vrouw", "nữ Không Phù Hợp", "Інші жіночі", "Другие женские", "أُنْثى غير متطابقة", "بے میل عورت",
    "مێینەی ناتەبایی", "न जुळणारी स्त्री", "बेमेल महिला", "बेमेल स्त्री", "অমিল মহিলা", "ਬੇਮੇਲ ਔਰਤ",
    "મિસમેચ ફીમેલ", "ଅସନ୍ତୁଷ୍ଟ ମହିଳା |", "பொருந்தாத பெண்", "సరిపోలని స్త్రీ", "ಹೊಂದಿಕೆಯಾಗದ ಹೆಣ್ಣು",
    "പൊരുത്തക്കേട് സ്ത്രീ", "ผู้หญิงอื่นๆ", "ភាពមិនស៊ីគ្នានឹងស្ត្រី", "失配女" -> SPEECH_POOL_FEMALE
    else -> group
}
