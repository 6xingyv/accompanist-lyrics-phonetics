package com.mocharealm.accompanist.lyrics.phonetics

import kotlin.test.*

class ProjectionFormattingTest {
    @Test fun crossWordLongMarksAndUnseparatedNasalUseOnlyAdjacentEligibleReadings() {
        val result = ParseResult("咲いたーー", listOf(
            reading(TextRange(0, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit("さい"))),
            reading(TextRange(2, 3), ReadingLocale.JAPANESE, listOf(ReadingUnit("た"))),
            reading(TextRange(3, 4), ReadingLocale.JAPANESE, listOf(ReadingUnit("ー"))),
            reading(TextRange(4, 5), ReadingLocale.JAPANESE, listOf(ReadingUnit("ー")))))
        val formatted = AsciiFormatter().format(result)
        assertEquals("sai taaa", formatted.latin)
        assertEquals(listOf("sai", "ta", "a", "a"), formatted.spans.map { it.latin })
        assertEquals(result.spans.map { it.range }, formatted.spans.map { it.sourceRange })
        assertNull(AsciiFormatter().formatRanges(result, listOf(TextRange(0, 3), TextRange(3, 5))))
        val interrupted = result.copy(text = "咲いた、ー", spans = result.spans.take(2) + listOf(
            PhoneticSpan(TextRange(3, 4), null, Resolution.PASSTHROUGH), result.spans.last()))
        assertEquals("sai ta,_", AsciiFormatter().format(interrupted).latin)
        val nasal = ParseResult("新案", listOf(
            reading(TextRange(0, 1), ReadingLocale.JAPANESE, listOf(ReadingUnit("しん"))),
            reading(TextRange(1, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit("あん")))))
        assertEquals("shin an", AsciiFormatter().format(nasal).latin)
        assertEquals("shin'an", AsciiFormatter(FormatOptions(separator = "")).format(nasal).latin)
        assertEquals("shin:an", AsciiFormatter(FormatOptions(separator = ":")).format(nasal).latin)
        assertNull(AsciiFormatter(FormatOptions(separator = "")).formatRanges(nasal, listOf(TextRange(0, 1), TextRange(1, 2))))
        val ambiguous = result.copy(spans = result.spans.mapIndexed { i, s -> if (i == 1) s.copy(resolution = Resolution.AMBIGUOUS) else s })
        assertEquals(listOf("_", "_", "_"), AsciiFormatter(FormatOptions(requireResolved = true)).format(ambiguous).spans.drop(1).map { it.latin })
        for ((a, b) in listOf("シン" to "アン", "ｼﾝ" to "ｱﾝ")) {
            val katakana = nasal.copy(spans = listOf(
                reading(TextRange(0, 1), ReadingLocale.JAPANESE, listOf(ReadingUnit(a))),
                reading(TextRange(1, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit(b)))))
            assertEquals("shin'an", AsciiFormatter(FormatOptions(separator = "")).format(katakana).latin)
        }
        val halfwidth = result.copy(spans = result.spans.take(2) + result.spans.drop(2).map {
            reading(it.range, ReadingLocale.JAPANESE, listOf(ReadingUnit("ｰ"))) })
        assertEquals("sai taaa", AsciiFormatter().format(halfwidth).latin)
    }
    @Test fun crossWordSokuonUsesTheFollowingReadingWithoutLosingSourceRanges() {
        val spans = listOf(
            reading(TextRange(0, 1), ReadingLocale.JAPANESE, listOf(ReadingUnit("と", sourceRange = TextRange(0, 1)))),
            reading(TextRange(1, 3), ReadingLocale.JAPANESE, listOf(ReadingUnit("いっ", sourceRange = TextRange(1, 3)))),
            reading(TextRange(3, 4), ReadingLocale.JAPANESE, listOf(ReadingUnit("た", sourceRange = TextRange(3, 4)))),
            PhoneticSpan(TextRange(4, 5), null, Resolution.PASSTHROUGH))
        val result = ParseResult("と言った。", spans)
        val formatter = AsciiFormatter()
        val formatted = formatter.format(result)
        assertEquals("to itta.", formatted.latin)
        assertEquals(listOf("to", "it", "ta", "."), formatted.spans.map { it.latin })
        assertEquals(spans.map { it.range }, formatted.spans.map { it.sourceRange })
        assertEquals(spans, result.spans)
        assertNull(formatter.formatRanges(result, listOf(TextRange(0, 3), TextRange(3, 5))))
        assertEquals(listOf(FormattedProjection("to itta.")), formatter.formatRanges(result, listOf(TextRange(0, 5))))
        val ambiguous = result.copy(spans = spans.mapIndexed { i, s -> if (i == 2) s.copy(resolution = Resolution.AMBIGUOUS) else s })
        assertEquals("to i__.", AsciiFormatter(FormatOptions(requireResolved = true)).format(ambiguous).latin)
        val interrupted = ParseResult("言っ、た", listOf(spans[1].copy(range = TextRange(0, 2)),
            PhoneticSpan(TextRange(2, 3), null, Resolution.PASSTHROUGH), spans[2].copy(range = TextRange(3, 4))))
        assertEquals("i_,ta", formatter.format(interrupted).latin)
        val chi = ParseResult("取っちゃ", listOf(
            reading(TextRange(0, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit("とっ"))),
            reading(TextRange(2, 4), ReadingLocale.JAPANESE, listOf(ReadingUnit("ちゃ")))))
        assertEquals("totcha", formatter.format(chi).latin)
        assertEquals("tottya", AsciiFormatter(FormatOptions(japanese = JapaneseRomanization.KUNREI)).format(chi).latin)
        for ((a, b) in listOf("トッ" to "チャ", "ﾄｯ" to "ﾁｬ")) {
            val katakana = chi.copy(spans = listOf(
                reading(TextRange(0, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit(a))),
                reading(TextRange(2, 4), ReadingLocale.JAPANESE, listOf(ReadingUnit(b)))))
            assertEquals("totcha", formatter.format(katakana).latin)
        }
    }
    private fun reading(range: TextRange, locale: ReadingLocale, units: List<ReadingUnit>): PhoneticSpan {
        val candidate = ReadingCandidate(Pronunciation(locale, units), listOf("test"))
        return PhoneticSpan(range, locale, Resolution.RESOLVED, candidate, listOf(candidate))
    }
    @Test fun boundariesSurviveCharacterAndWordTimingPartitions() {
        val result = ParseResult("着迷看着", listOf(
            reading(TextRange(0, 2), ReadingLocale.MANDARIN_CN, listOf(
                ReadingUnit("zhao", 2, TextRange(0, 1)), ReadingUnit("mi", 2, TextRange(1, 2)))),
            reading(TextRange(2, 4), ReadingLocale.MANDARIN_CN, listOf(
                ReadingUnit("kan", 4, TextRange(2, 3)), ReadingUnit("zhe", 5, TextRange(3, 4)))),
        ))
        for (separator in listOf(" ", ":", "")) {
            val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS, separator = separator))
            for (ranges in listOf((0..3).map { TextRange(it, it + 1) }, listOf(TextRange(0, 2), TextRange(2, 4)))) {
                val projected = assertNotNull(formatter.formatRanges(result, ranges))
                assertEquals("", projected.first().separatorBefore)
                assertEquals(formatter.format(result).latin, projected.joinToString("") { it.separatorBefore + it.latin })
                assertTrue(projected.drop(1).all { it.separatorBefore == separator })
            }
        }
    }
    @Test fun omittedLatinAndSupplementaryCharactersKeepOriginalAlignment() {
        val result = ParseResult("𠀀hola迷", listOf(
            reading(TextRange(0, 2), ReadingLocale.MANDARIN_CN, listOf(ReadingUnit("he", 1, TextRange(0, 2)))),
            PhoneticSpan(TextRange(2, 6), null, Resolution.PASSTHROUGH),
            reading(TextRange(6, 7), ReadingLocale.MANDARIN_CN, listOf(ReadingUnit("mi", 2, TextRange(6, 7)))),
        ))
        val formatter = AsciiFormatter(FormatOptions(passthrough = PassthroughStyle.OMIT))
        assertEquals(listOf(FormattedProjection("he"), FormattedProjection(""), FormattedProjection("mi", " ")),
            formatter.formatRanges(result, listOf(TextRange(0, 2), TextRange(2, 6), TextRange(6, 7))))
        assertFailsWith<IllegalArgumentException> { formatter.formatRanges(result, listOf(TextRange(0, 1), TextRange(1, 7))) }
        val latin = ParseResult("tonight", listOf(PhoneticSpan(TextRange(0, 7), null, Resolution.PASSTHROUGH)))
        assertEquals(listOf(FormattedProjection("to"), FormattedProjection("night")),
            AsciiFormatter().formatRanges(latin, listOf(TextRange(0, 2), TextRange(2, 7))))
    }
    @Test fun indivisibleAndContextualJapaneseUseWholeCaptionFallback() {
        val formatter = AsciiFormatter()
        val word = ParseResult("日本", listOf(reading(TextRange(0, 2), ReadingLocale.JAPANESE, listOf(ReadingUnit("にほん")))))
        assertEquals(listOf(FormattedProjection("nihon")), formatter.formatRanges(word, listOf(TextRange(0, 2))))
        assertNull(formatter.formatRanges(word, listOf(TextRange(0, 1), TextRange(1, 2))))
        val kana = ParseResult("んあ", listOf(reading(TextRange(0, 2), ReadingLocale.JAPANESE, listOf(
            ReadingUnit("ん", sourceRange = TextRange(0, 1)), ReadingUnit("あ", sourceRange = TextRange(1, 2))))))
        assertEquals("n'a", formatter.format(kana).latin)
        assertNull(formatter.formatRanges(kana, listOf(TextRange(0, 1), TextRange(1, 2))),
            "Known source ranges alone cannot preserve contextual Romanization across this cut")
        assertEquals(listOf(FormattedProjection("n'a")), formatter.formatRanges(kana, listOf(TextRange(0, 2))))
    }
}
