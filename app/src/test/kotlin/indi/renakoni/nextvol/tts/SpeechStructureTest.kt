package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechStructureTest {
    private val dialogue = SpeechTextSettings(dialogue = true)
    private val first = SpeechPersonVoice("甲", "voice-a")
    private val second = SpeechPersonVoice("乙", "voice-b")
    private val names: (String, SpeechContextDirection) -> SpeechPersonVoice? = { text, direction ->
        if (direction == SpeechContextDirection.Explicit) when (text) {
            "甲" -> first
            "乙" -> second
            else -> null
        } else null
    }

    private fun structure(text: String, settings: SpeechTextSettings = dialogue,
        incoming: SpeechContext = SpeechContext(),
        resolve: ((String, SpeechContextDirection) -> SpeechPersonVoice?)? = null) =
        speechStructure(SpeechMappedText.original(text), settings, incoming, resolve) {}

    @Test fun disabledDialogueAndEmptyInputLeaveContextUntouched() {
        val context = SpeechContext(true, listOf(SpeechContextText("上一句", true, first)), listOf(null, "voice-a"))
        val disabled = structure("“<<甲>>你好”", SpeechTextSettings(), context, names)
        assertEquals("“<<甲>>你好”", disabled.parts.single().content.text)
        assertFalse(disabled.parts.single().dialogue)
        assertNull(disabled.parts.single().speakerHint)
        assertEquals(context, disabled.nextContext)
        assertEquals(context, structure("", incoming = context).nextContext)
    }

    @Test fun quoteStatesDistinguishDialogueFromQuotedNarrationWithoutNestingOrNewlineReset() {
        val cases = listOf(
            "他说：“你好。”" to listOf("他说：" to false, "“你好。”" to true),
            "所谓“高手”来了。" to listOf("所谓" to false, "“高手”" to false, "来了。" to false),
            "“第一行\n第二行”" to listOf("“第一行\n第二行”" to true),
            "“外“内”外”" to listOf("“外“内”" to true, "外”" to true),
            "未开头却结束”" to listOf("未开头却结束”" to true),
            "「你好」" to listOf("「你好」" to false),
            "他“没说完" to listOf("他" to false, "“没说完" to true),
            "\"第一句\" \"第二句\"" to listOf("\"第一句\"" to true, " \"第二句\"" to true),
        )
        for ((input, expected) in cases) {
            val parts = structure(input).parts
            assertEquals(input, expected, parts.map { it.content.text to it.dialogue })
            assertEquals(input, parts.joinToString("") { it.content.text })
        }
    }

    @Test fun continuationRetainsPreviousPersonWithoutNeedingTextMemory() {
        val settings = dialogue.copy(continueDialogue = true)
        val before = structure("“<<甲>>还没说完", settings, resolve = names)
        assertTrue(before.nextContext.openDialogue)
        val after = structure("接着说完”", settings, before.nextContext, names)
        assertTrue(after.parts.single().dialogue)
        assertFalse(after.parts.single().changeVoice)
        assertEquals(first, after.parts.single().personVoice)
        assertFalse(after.nextContext.openDialogue)
        assertFalse(structure("“没说完").nextContext.openDialogue)
        assertFalse(structure("接着说完", settings).parts.single().dialogue)
    }

    @Test fun controlMarkersKeepTheirTextWithoutChangingQuoteStateOrSupplyingAPerson() {
        val mark = "[[<<甲>>“参数”:“值”]]"
        val result = structure("“开始${mark}结束”", resolve = names)
        assertEquals(listOf("“开始", mark, "结束”"), result.parts.map { it.content.text })
        assertEquals(listOf(true, false, true), result.parts.map { it.dialogue })
        assertTrue(result.parts[1].controlMark)
        assertFalse(result.parts.last().changeVoice)
        assertTrue(result.parts.all { it.speakerHint == null && it.speaker == null })
        assertEquals(listOf("结束”"), result.nextContext.previousText.map { it.text })
        val narration = structure("说明${mark}文字")
        assertEquals(listOf("文字", "说明"), narration.nextContext.previousText.map { it.text })
        assertTrue(narration.parts.none { it.dialogue })
        val context = narration.nextContext
        assertEquals(context, structure(mark, incoming = context).nextContext)
    }

    @Test fun onlyResolvedFirstMarkerRemovesMarkersAndKeepsSurvivingUtf16Origins() {
        val input = "“<<甲>>𠀀<<乙>>好”"
        val unresolved = structure(input)
        assertEquals(input, unresolved.parts.single().content.text)
        assertEquals("甲", unresolved.parts.single().speakerHint)
        assertNull(unresolved.parts.single().speaker)
        val resolved = structure(input, resolve = names).parts.single()
        assertEquals("“𠀀好”", resolved.content.text)
        assertEquals(first, resolved.personVoice)
        val original = input.indexOf("𠀀")
        assertEquals(original to original + 2, resolved.content.range(1, 3))
        val unknown = "“<<未知>>你好<<甲>>”"
        assertEquals(unknown, structure(unknown, resolve = names).parts.single().content.text)
        val multiline = "“<<甲\n乙>>你好”"
        assertNull(structure(multiline, resolve = names).parts.single().speakerHint)
    }

    @Test fun contextPrefersPrecedingNarrationAndPropagatesFollowingPersonBackward() {
        val calls = mutableListOf<Pair<String, SpeechContextDirection>>()
        val resolver: (String, SpeechContextDirection) -> SpeechPersonVoice? = { text, direction ->
            calls += text to direction
            when (text to direction) {
                "甲说：" to SpeechContextDirection.Previous -> first
                "乙答。" to SpeechContextDirection.Next -> second
                else -> null
            }
        }
        val previous = structure("甲说：“你好。”乙答。", resolve = resolver)
        val part = previous.parts[1]
        assertEquals(first, part.personVoice)
        assertEquals(listOf("甲说："), part.beforeText)
        assertEquals(listOf("乙答。"), part.afterText)
        assertEquals(listOf("甲说：" to SpeechContextDirection.Previous), calls)

        val following = structure("“第一句。”“第二句。”乙答。", resolve = resolver)
        assertEquals(listOf(second, second), following.parts.filter { it.dialogue }.map { it.personVoice })
        assertEquals(second, following.nextContext.previousText.single().personVoice)
    }

    @Test fun textMemoryStoresTrailingNarrationInReverseAndOnlyResolvesFirstStructuralPart() {
        val context = structure("前“中”甲说：").nextContext
        assertEquals(listOf("甲说：", "“中”", "前"), context.previousText.map { it.text })
        val resolver: (String, SpeechContextDirection) -> SpeechPersonVoice? = { text, direction ->
            first.takeIf { text == "甲说：" && direction == SpeechContextDirection.Next }
        }
        assertNull(structure("“你好”", incoming = context, resolve = resolver).parts.single().speaker)
        val settings = dialogue.copy(recordText = true)
        assertEquals(first, structure("“你好”", settings, context, resolver).parts.single().personVoice)
        assertNull(structure("前言。“你好”", settings, context, resolver).parts.last().speaker)
        assertEquals(context.previousText.map { it.text }, structure("“你好”", settings, context).parts.single().rememberedText)
        assertTrue(structure("“你好”", incoming = context).parts.single().rememberedText.isEmpty())
        assertTrue(structure("“<<甲>>你好”", settings, context, names).parts.single().rememberedText.isEmpty())
        assertEquals(listOf("甲说：", "“中”", "前"), context.previousText.map { it.text })
    }

    @Test fun voiceMemoryAlternatesTwoDialogueVoicesAndPreservesEmptySlots() {
        val settings = dialogue.copy(recordVoice = true)
        val firstOnly = structure("“<<甲>>一句”", settings, resolve = names)
        assertEquals(listOf(null, "voice-a"), firstOnly.nextContext.recentVoices)
        val result = structure("“<<甲>>一句”“<<乙>>二句”“三句”“四句”", settings, resolve = names)
        assertEquals(listOf(null, null, "voice-a", "voice-b"), result.parts.map { it.previousVoice })
        assertEquals(listOf("voice-a", "voice-b"), result.nextContext.recentVoices)
        assertNull(result.parts[2].speaker)
        assertEquals(emptyList<String?>(), structure("“<<甲>>一句”", resolve = names).nextContext.recentVoices)
        val untouched = structure("“无角色解析器”", settings, firstOnly.nextContext)
        assertNull(untouched.parts.single().previousVoice)
        assertEquals(firstOnly.nextContext.recentVoices, untouched.nextContext.recentVoices)
    }

    @Test fun unresolvedNarrationCueUsesEmptyVoiceWhileNarrationDoesNotResetMemory() {
        val settings = dialogue.copy(recordVoice = true)
        val unresolved = structure("旁白。“你好”", settings, resolve = names)
        assertEquals(SpeechPersonVoice("", ""), unresolved.parts.last().personVoice)
        assertEquals(listOf(null, ""), unresolved.nextContext.recentVoices)
        val context = SpeechContext(recentVoices = listOf("voice-a", "voice-b"))
        val narration = structure("前“中”后“又”末。", settings, context, names)
        assertEquals(5, narration.parts.size)
        assertTrue(narration.parts.none { it.dialogue })
        assertEquals(context.recentVoices, narration.nextContext.recentVoices)
    }
}
