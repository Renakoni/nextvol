package hnovel.speech

/** Imported speakers start at 1x speed; other declared settings retain their package values. */
internal fun SpeechPackageVoice.withImportDefaults(engine: String): SpeechPackageVoice {
    val fields = raw.values.toMutableMap()
    for (key in listOf("speed", "volume", "sampleRate", "pitch", "locale")) {
        if (fields[key] != null && fields[key] !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, key)
    }
    fun set(key: String, value: String) { fields[key] = SpeechPackageValue.Scalar(value, null, false) }
    set("speed", "1.0")
    if (fields["volume"].text().isNullOrBlank()) set("volume", "0.0")
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
