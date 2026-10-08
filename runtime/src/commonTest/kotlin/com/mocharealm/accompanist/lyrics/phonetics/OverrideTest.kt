package com.mocharealm.accompanist.lyrics.phonetics

import kotlin.test.*

class OverrideTest {
    @Test fun suppliedPronunciationWorksWithoutPackAndKeepsWordAlignment() {
        val engine = PhoneticEngine()
        val reading = Pronunciation(ReadingLocale.JAPANESE, listOf(ReadingUnit("あした")))
        val options = ParseOptions(overrides = listOf(ReadingOverride(TextRange(2, 4), reading, "song-editor")))
        val text = "🎵星空!"
        val result = engine.parse(text, options)
        val word = result.spans.single { it.range == TextRange(2, 4) }
        assertEquals(reading, word.selected!!.pronunciation)
        assertEquals(listOf("song-editor"), word.selected.sourceIds)
        assertEquals(Resolution.RESOLVED, word.resolution)
        val formatter = AsciiFormatter(FormatOptions(passthrough = PassthroughStyle.OMIT))
        assertEquals("ashita", formatter.format(result).latin)
        assertNull(formatter.formatRange(result, TextRange(2, 3)))
        assertEquals(result, engine.parse(text, options, ParseWorkspace(0)))
        assertEquals(result, engine.parse(text, options, ParseWorkspace(256)))
        assertEquals(Resolution.LANGUAGE_AMBIGUOUS, engine.parse("星空").spans.single().resolution)
    }
    @Test fun explicitAlignedUnitsProjectAndInvalidRangesAreRejected() {
        val engine = PhoneticEngine()
        val reading = Pronunciation(ReadingLocale.MANDARIN_CN, listOf(ReadingUnit("yin", 2, TextRange(0, 1)), ReadingUnit("hang", 2, TextRange(1, 2))))
        val supplied = ReadingOverride(TextRange(0, 2), reading)
        val result = engine.parse("银行", ParseOptions(overrides = listOf(supplied)))
        assertEquals("hang", AsciiFormatter().formatRange(result, TextRange(1, 2)))
        assertFailsWith<IllegalArgumentException> { engine.parse("银行", ParseOptions(overrides = listOf(supplied, supplied))) }
        assertFailsWith<IllegalArgumentException> { engine.parse("𠀀", ParseOptions(overrides = listOf(supplied.copy(range = TextRange(0, 1))))) }
        val incomplete = reading.copy(units = listOf(ReadingUnit("hang", 2, TextRange(1, 2))))
        assertFailsWith<IllegalArgumentException> { engine.parse("银行", ParseOptions(overrides = listOf(supplied.copy(pronunciation = incomplete)))) }
    }
}
