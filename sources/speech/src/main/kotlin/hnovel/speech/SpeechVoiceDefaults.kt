package hnovel.speech

/** MultiTTS's YAML import fills these fields after reading the speaker, without replacing declared values. */
internal fun SpeechPackageVoice.withImportDefaults(engine: String): SpeechPackageVoice {
    val fields = raw.values.toMutableMap()
    for (key in listOf("speed", "volume", "sampleRate", "pitch", "locale")) {
        if (fields[key] != null && fields[key] !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, key)
    }
    fun set(key: String, value: String) { fields[key] = SpeechPackageValue.Scalar(value, null, false) }
    for (key in listOf("speed", "volume")) if (fields[key].text().isNullOrBlank()) set(key, "0.0")
    if (fields["sampleRate"].text().isNullOrBlank() || fields["sampleRate"].text()?.toIntOrNull() == 0) set("sampleRate", "16000")
    if (fields["pitch"].text().isNullOrBlank() || fields["pitch"].text()?.toFloatOrNull() == 0f) set("pitch", "1.0")
    if (fields["locale"].text().isNullOrEmpty()) {
        val language = if (engine in setOf("microsoft", "mstrans", "edgetts")) code.split('-').take(2).joinToString("-")
            else if (name.isNotEmpty() && name.matches(Regex("^[-+_a-zA-Z0-9() ]+\\d*$"))) "en-US" else "zh-CN"
        set("locale", language)
    }
    return SpeechPackageVoice(code, name, SpeechPackageValue.Mapping(fields, raw.tag))
}

fun speechPackageAvatarPath(engine: String, avatar: String): String? =
    "$engine/avatar/$avatar".takeIf { safeSpeechPackagePath(engine) && safeSpeechPackagePath(avatar) && safeSpeechPackagePath(it) }
