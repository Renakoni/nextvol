package indi.renakoni.nextvol.tts

import hnovel.speech.SpeechPackagePreview
import hnovel.speech.previewSpeechPackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeechConfigurationImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun document(name: String, text: String) = previewSpeechPackage(
        temporary.newFile(name).apply { writeText(text.trimIndent()) })

    @Test fun originalAudiosRolesSelectTheirImportedVoicesInTheReaderPlan() = runBlocking {
        val file = File(javaClass.getResource("/multitts-config/roles.yaml")!!.toURI())
        val voices = listOf("78", "71", "70").associate { code ->
            "audio_audio$code" to SpeechVoiceRef(httpSource = code, label = code)
        }
        val imported = previewSpeechConfiguration(previewSpeechPackage(file), voices, "Default")
        assertNull(imported.error)
        assertTrue(imported.unsupported.isEmpty())
        assertEquals(listOf("章节转场", "章节开始", "章中转场"), imported.roles!!.map { it.name })
        assertTrue(imported.unresolvedVoices.isEmpty())
        val narrator = SpeechVoiceRef(httpSource = "narrator")
        val settings = imported.applyTo(SpeechSettings(voiceRoutingConfigured = true,
            narrationVoices = listOf(narrator), dialogueVoices = listOf(narrator),
            text = SpeechTextSettings(dialogue = true, separateEnglish = false, maxSentenceLength = 256)), replace = false)
        val plan = prepareSpeechPlan(SpeechChapter("book", "chapter", "", "",
            "开始（章节开始）中间（章中转场）结束（章节转场）"), settings) {}
        val matches = plan.segments.filter { it.roleMatch != null }
        assertEquals(listOf("章节开始", "章中转场", "章节转场"), matches.map { it.roleMatch!!.ruleName })
        assertEquals(listOf("71", "70", "78"), matches.map { it.selectedVoice!!.reference.httpSource })
    }

    @Test fun mergingKeepsOriginalOrderingAndStableIdentitiesWithoutChangingSettings() {
        val rules = document("rules.yaml", """
            replaces:
            - { name: old, source: ':', target: '：', regex: false }
            - { group: g, name: new, source: 甲, target: 乙 }
            roles:
            - { group: '', name: old, regex: 'lang:en', mode: 1, activate: true, speakerId: missing }
            - { group: g, name: new, regex: '[', isAbstract: true, speakerId: '@mismatchMale' }
        """)
        val pool = document("voice_pool.yaml", """
            失配男:
            - !!org.nobody.multitts.tts.role.VoiceItem
              { name: old, value: missing, group: 失配男, activate: true }
            - { name: new, value: missing }
        """)
        val imported = previewSpeechConfiguration(SpeechPackagePreview("configuration",
            documents = rules.documents + pool.documents), emptyMap(), "Default")
        assertNull(imported.error)
        assertTrue(imported.replacements!!.first().wholeText)
        val importedRoles = imported.roles!!
        val importedVoices = imported.voiceItems!!
        assertFalse(importedRoles.last().activate)
        assertFalse(importedRoles.last().matchBefore)
        assertEquals(SPEECH_POOL_MALE, importedRoles.last().speaker!!.voice)
        assertTrue(importedVoices.all { it.group == SPEECH_POOL_MALE })
        assertFalse(importedVoices.last().activate)
        assertEquals(listOf("missing"), imported.unresolvedVoices.map { it.externalId })
        val oldVoice = SpeechVoiceRef(httpSource = "old")
        val current = SpeechSettings(
            replacements = listOf(SpeechReplacementRule(id = "r-old", group = "Default", name = "old"),
                SpeechReplacementRule(id = "r-keep", group = "g", name = "keep")),
            roleRules = listOf(SpeechRoleRule(id = "c-old", group = "Default", name = "old"),
                SpeechRoleRule(id = "c-keep", group = "g", name = "keep")),
            voiceItems = listOf(SpeechVoiceItem(SPEECH_POOL_MALE, oldVoice, "old", id = "v-old"),
                SpeechVoiceItem(SPEECH_POOL_MALE, oldVoice, "keep", id = "v-keep")),
            text = SpeechTextSettings(dialogue = false, detectLanguage = false),
        )
        assertEquals(3, imported.conflicts(current).size)
        val merged = imported.applyTo(current, replace = false)
        assertEquals(listOf("old", "keep", "new"), merged.replacements.map { it.name })
        assertEquals(listOf("keep", "old", "new"), merged.roleRules.map { it.name })
        assertEquals(listOf("keep", "old", "new"), merged.voiceItems.map { it.name })
        assertEquals("r-old", merged.replacements.first().id)
        assertEquals("c-old", merged.roleRules[1].id)
        assertEquals("v-old", merged.voiceItems[1].id)
        assertEquals(merged, imported.applyTo(merged, replace = false))
        assertEquals(current.text, merged.text)
        val onlyRoles = imported.copy(replacements = null, voiceItems = null).applyTo(current, replace = true)
        assertEquals(listOf("old", "new"), onlyRoles.roleRules.map { it.name })
        assertEquals(current.replacements, onlyRoles.replacements)
        assertEquals(current.voiceItems, onlyRoles.voiceItems)
        val duplicates = imported.copy(roles = listOf(importedRoles.first(), importedRoles.first().copy(id = "another-row")))
        val repeatedNames = duplicates.applyTo(current, replace = true)
        assertEquals(2, repeatedNames.roleRules.map { it.id }.toSet().size)
        assertEquals("c-old", repeatedNames.roleRules.first().id)
        assertEquals(repeatedNames, duplicates.applyTo(repeatedNames, replace = true))
    }

    @Test fun staticImportReportsUnknownFieldsAndRejectsMalformedKnownFieldsAsOneChange() {
        val preview = document("roles.yaml", """
            !!org.nobody.multitts.tts.role.RoleConfig
            roles:
            - name: broken-pattern
              regex: '['
              activate: true
              extra: !!java.net.URL 'https://example.invalid/private-token'
        """)
        val imported = previewSpeechConfiguration(preview, emptyMap(), "Default")
        assertNull(imported.error)
        assertEquals("[", imported.roles!!.single().regex)
        assertEquals(listOf("roles.yaml.roles[0].extra"), imported.unsupported)
        val malformed = document("malformed.yaml", """
            replaces:
            - { name: valid, source: old, target: new }
            roles:
            - { name: malformed, activate: { nested: true } }
        """)
        val rejected = previewSpeechConfiguration(malformed, emptyMap(), "Default")
        assertEquals("malformed.yaml.roles[0].activate", rejected.error)
        assertFalse(rejected.hasChanges)
        val offline = document("config.yaml", "sherpa: [{code: voice, futureField: private-value}]")
        assertEquals(listOf("config.yaml.sherpa[0].futureField"),
            previewSpeechConfiguration(offline, emptyMap(), "Default").unsupported)
    }

    @Test fun reimportingPreservesPersonIdentityOnlyForTheSamePatternAndMode() {
        val person = SpeechRoleRule(name = "林逸", group = "文本", regex = "(林逸|小林)", isAbstract = true,
            speaker = SpeechVoiceRef(voice = SPEECH_POOL_MALE), person = SpeechRolePerson("林逸", listOf("小林")))
        val settings = SpeechSettings(roleRules = listOf(person))
        val incoming = person.copy(id = "imported", person = null)
        val imported = SpeechConfigurationImport(roles = listOf(incoming))
        assertEquals(person, imported.applyTo(settings, replace = false).roleRules.single())
        val replaced = imported.copy(roles = listOf(incoming.copy(regex = "张三"))).applyTo(settings, replace = false)
        assertEquals(person.id, replaced.roleRules.single().id)
        assertNull(replaced.roleRules.single().person)
        val textRule = imported.copy(roles = listOf(incoming.copy(mode = 0))).applyTo(settings, replace = false)
        assertNull(textRule.roleRules.single().person)
    }
}
