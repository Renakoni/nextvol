package indi.renakoni.nextvol.tts

import java.util.Locale

/** MultiTTS O6.c.e/f: detector-specific language codes used by the language rule editor. */
fun speechDetectionLanguages(algorithm: Int): List<String> = when (algorithm) {
    300 -> optimaizeLanguages
    301 -> mediaPipeLanguages
    else -> emptyList()
}

internal fun speechLanguageLabel(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(code).getDisplayName(locale).ifEmpty { code }

private val optimaizeLanguages = "af an ar ast be bg bn br ca cs cy da de el en es et eu fa fi fr ga gl gu he hi hr ht hu id is it ja km kn ko lt lv mk ml mr ms mt ne nl no oc pa pl pt ro ru sk sl so sq sr sv sw ta te th tl tr uk ur vi yi zh-CN zh-TW".split(' ')
private val mediaPipeLanguages = "af am ar ar-Latn az be bg bg-Latn bn bs ca ceb co cs cy da de el el-Latn en eo es et eu fa fi fil fr fy ga gd gl gu ha haw hi hi-Latn hmn hr ht hu hy id ig is it iw ja ja-Latn jv ka kk km kn ko ku ky la lb lo lt lv mg mi mk ml mn mr ms mt my ne nl no ny pa pl ps pt ro ru ru-Latn sd si sk sl sm sn so sq sr st su sv sw ta te tg th tr uk ur uz vi xh yi yo zh zh-Latn zu".split(' ')
