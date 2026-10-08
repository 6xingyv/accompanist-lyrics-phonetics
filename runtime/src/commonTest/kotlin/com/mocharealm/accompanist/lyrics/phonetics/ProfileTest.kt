package com.mocharealm.accompanist.lyrics.phonetics

import kotlin.test.*

class ProfileTest {
    private class NativeProfile : PhoneticProfile {
        val calls = mutableListOf<TextRange>()
        override fun matches(codePoint: Int) = isHangulCodePoint(codePoint)
        override fun resolve(text: String, range: TextRange, options: ParseOptions, workspace: ParseWorkspace): LanguageResult {
            calls.add(range)
            val candidate = ReadingCandidate(
                Pronunciation(null, listOf(ReadingUnit(range.textIn(text), sourceRange = range)), "test-hangul", "ko"),
                listOf("original-test"),
            )
            return LanguageResult(listOf(PhoneticSpan(range, null, Resolution.RESOLVED, candidate, listOf(candidate))))
        }
    }
    @Test fun nativeNotationIsResolvedBeforeFormattingAndKeepsOriginalRanges() {
        val first = NativeProfile(); val shadowed = NativeProfile()
        val engine = PhoneticEngine(profiles = listOf(first, shadowed))
        val text = "🎵한국hola canción"
        val parsed = engine.parse(text, ParseOptions(hints = listOf(
            LanguageHint(TextRange(2, 3), "ko"), LanguageHint(TextRange(3, 4), "ko"),
        )))
        assertEquals(listOf(TextRange(2, 4)), first.calls, "Equal adjacent tags preserve context")
        assertTrue(shadowed.calls.isEmpty(), "Profile precedence is first match")
        assertEquals("한국", parsed.spans[1].selected!!.pronunciation.units.single().value)
        assertEquals(text, parsed.spans.joinToString("") { it.range.textIn(text) })
        val formatter = AsciiFormatter(FormatOptions(passthrough = PassthroughStyle.OMIT), listOf(object : PronunciationFormatter {
            override val notation = "test-hangul"
            override fun format(pronunciation: Pronunciation, options: FormatOptions) = "hangug"
        }))
        assertEquals("hangug", formatter.format(parsed).latin)
        assertNull(formatter.formatRange(parsed, TextRange(2, 3)), "Native run alignment is indivisible")
        assertEquals("", formatter.formatRange(parsed, TextRange(4, text.length)), "Latin text has no caption")
        val absent = AsciiFormatter(FormatOptions(passthrough = PassthroughStyle.OMIT))
        assertEquals("_", absent.format(parsed).latin, "Missing formatter must not leak native script")
    }
    @Test fun emptyPacksStillReportUnknownHanAndMalformedProfileCannotCorruptOffsets() {
        val empty = PhoneticEngine()
        assertEquals(Resolution.UNKNOWN, empty.parse("行", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)).spans.single().resolution)
        assertEquals(Resolution.LANGUAGE_AMBIGUOUS, empty.parse("行").spans.single().resolution)
        val malformed = object : PhoneticProfile {
            override fun matches(codePoint: Int) = true
            override fun resolve(text: String, range: TextRange, options: ParseOptions, workspace: ParseWorkspace) =
                LanguageResult(listOf(PhoneticSpan(TextRange(1, 2), null, Resolution.PASSTHROUGH)))
        }
        val workspace = ParseWorkspace()
        assertFailsWith<IllegalArgumentException> { PhoneticEngine(profiles = listOf(malformed)).parse("AB", workspace = workspace) }
        assertEquals("AB", AsciiFormatter().format(empty.parse("AB", workspace = workspace)).latin)
    }
    @Test fun formatterRejectsNonAsciiAndDuplicateNotations() {
        val profile = NativeProfile()
        val parsed = PhoneticEngine(profiles = listOf(profile)).parse("한국")
        val invalid = object : PronunciationFormatter {
            override val notation = "test-hangul"
            override fun format(pronunciation: Pronunciation, options: FormatOptions) = "한국"
        }
        assertFailsWith<IllegalArgumentException> { AsciiFormatter(formatters = listOf(invalid)).format(parsed) }
        assertFailsWith<IllegalArgumentException> { AsciiFormatter(formatters = listOf(invalid, invalid)) }
    }
}
