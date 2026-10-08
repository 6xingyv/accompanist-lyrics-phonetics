package com.mocharealm.accompanist.lyrics.phonetics

import kotlin.test.*

class EngineTest {
    @Test fun weakPreferencesCannotHideSentenceOrSegmentationAlternatives() {
        val lexicon = TestLexicon(ReadingLocale.MANDARIN_CN, mapOf(
            "银行" to listOf(entry("yin2 hang2", -1900).copy(weakPreference = -2000, selectionRules = listOf("test-weak"))),
            "银" to listOf(entry("yin2", 0)), "行" to listOf(entry("xing2", 0))))
        val parser = PhoneticEngine(listOf(LexiconResolver(lexicon)))
        val options = ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN, ambiguityCostWindow = 0)
        val workspace = ParseWorkspace(1)
        val result = parser.parse("银行", options, workspace)
        assertEquals("yin hang", AsciiFormatter().format(result).latin)
        assertEquals(Resolution.AMBIGUOUS, result.spans.single().resolution)
        assertEquals(100L, result.spans.single().selected!!.uncertaintyCostDelta)
        assertTrue(result.ambiguities.any { it.alternatives.any { a -> a.range == TextRange(0, 1) } })
        assertEquals(result, parser.parse("银行", options, ParseWorkspace(0)))
        parser.parse("银行".repeat(100), options, workspace)
        assertEquals(result, parser.parse("银行", options, workspace))
        assertTrue(workspace.lattice.all { it.isEmpty() } && workspace.starts.all { it.isEmpty() })
    }
    @Test fun japaneseNumericCounterContextDoesNotLeakIntoCacheOrAcrossLanguageHints() {
        val lexicon = TestLexicon(ReadingLocale.JAPANESE, mapOf(
            "年" to listOf(entry("とし", 100, false), entry("ねん", 500, false)),
            "月" to listOf(entry("つき", 100, false), entry("がつ", 600, false))))
        val engine = PhoneticEngine(listOf(LexiconResolver(lexicon)))
        val options = ParseOptions(defaultLocale = ReadingLocale.JAPANESE)
        for (capacity in listOf(0, 1, 256)) {
            val workspace = ParseWorkspace(capacity)
            for ((text, expected) in listOf("年" to "toshi", "5年" to "gonen", "年" to "toshi",
                "１２月" to "juunigatsu", "月" to "tsuki")) {
                val result = engine.parse(text, options, workspace)
                assertEquals(expected, AsciiFormatter().format(result).latin)
                assertEquals(result, engine.parse(text, options, ParseWorkspace(0)))
                assertEquals(text, result.spans.joinToString("") { it.range.textIn(text) })
            }
            val separate = options.copy(hints = listOf(LanguageHint(TextRange(0, 1), "en"), LanguageHint(TextRange(1, 2), "ja")))
            assertEquals("5 toshi", AsciiFormatter().format(engine.parse("5年", separate, workspace)).latin)
            val together = options.copy(hints = listOf(LanguageHint(TextRange(0, 2), "ja")))
            assertEquals("gonen", AsciiFormatter().format(engine.parse("5年", together, workspace)).latin)
        }
    }
    private class TestLexicon(override val locale: ReadingLocale, val entries: Map<String, List<Lexeme>>) : ReadingLexicon {
        override val maxKeyLength = entries.keys.maxOfOrNull { it.length } ?: 1
        override fun lookup(surface: String, workspace: ParseWorkspace) = entries[surface].orEmpty()
    }
    private fun entry(reading: String, cost: Int = 900, aligned: Boolean = true) = Lexeme(reading, cost, 0, 0, aligned, listOf("original-test"))
    private fun engine(locale: ReadingLocale = ReadingLocale.MANDARIN_CN) = PhoneticEngine(listOf(LexiconResolver(TestLexicon(locale, mapOf(
        "行" to listOf(entry("hang2"), entry("xing2")), "银" to listOf(entry("yin2")),
        "银行" to listOf(entry("yin2 hang2", 100)), "𠀀" to listOf(entry("he1")),
        "重重" to listOf(entry("chong2 chong2", 100), entry("zhong4 zhong4", 100)),
    )))))
    @Test fun contextAndAlignment() {
        val result = engine().parse("A银行𠀀🙂!", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN))
        assertEquals("A yin hang he_!", AsciiFormatter().format(result).latin)
        assertEquals(TextRange(1, 3), result.spans[1].range)
        assertEquals(TextRange(3, 5), result.spans[2].range)
        assertEquals(TextRange(1, 2), result.spans[1].selected!!.pronunciation.units[0].sourceRange)
        assertEquals("hang", AsciiFormatter().formatRange(result, TextRange(2, 3)))
        assertEquals(result.text, result.spans.joinToString("") { it.range.textIn(result.text) })
    }
    @Test fun unresolvedReadingsAndNoHanLanguageGuess() {
        val result = engine().parse("行", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN))
        assertEquals(Resolution.AMBIGUOUS, result.spans.single().resolution)
        assertEquals(2, result.spans.single().candidates.size)
        assertTrue(result.ambiguities.isNotEmpty())
        assertEquals("_", AsciiFormatter(FormatOptions(requireResolved = true)).format(result).latin)
        val auto = engine().parse("行")
        assertEquals(Resolution.LANGUAGE_AMBIGUOUS, auto.spans.single().resolution)
        assertEquals(1, auto.languageAlternatives.size)
    }
    @Test fun cacheAndWorkspaceDoNotChangeResults() {
        val parser = engine(); val options = ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)
        val expected = parser.parse("银行行重重", options, ParseWorkspace(0))
        for (capacity in listOf(0, 1, 2, 256)) {
            val workspace = ParseWorkspace(capacity)
            repeat(5) { assertEquals(expected, parser.parse("银行行重重", options, workspace)) }
            workspace.trim()
            assertEquals(expected, parser.parse("银行行重重", options, workspace))
        }
    }
    @Test fun hintsAndUnsupportedLanguages() {
        assertEquals(ReadingLocale.MANDARIN_TW, ReadingLocale.fromLanguageTag("zh-Hant-TW"))
        assertEquals(ReadingLocale.CANTONESE_HK, ReadingLocale.fromLanguageTag("zh-HK"))
        val result = engine().parse("行", ParseOptions(hints = listOf(LanguageHint(TextRange(0, 1), "ko"))))
        assertEquals(Resolution.UNKNOWN, result.spans.single().resolution)
        assertFailsWith<IllegalArgumentException> { engine().parse("𠀀", ParseOptions(hints = listOf(LanguageHint(TextRange(0, 1), "zh")))) }
        assertFailsWith<IllegalArgumentException> { engine().parse("银行", ParseOptions(hints = listOf(LanguageHint(TextRange(0, 2), "zh"), LanguageHint(TextRange(1, 2), "ja")))) }
    }
    @Test fun kanaIsParsedBeforeFormattingAndSupportsHalfWidth() {
        val parser = PhoneticEngine(listOf(LexiconResolver(TestLexicon(ReadingLocale.JAPANESE, emptyMap()))))
        val examples = mapOf("きゃっと" to "kyatto", "ｶﾞｯﾂﾎﾟｰｽﾞ" to "gattsupoozu", "しんよう" to "shin'you", "まっちゃ" to "matcha", "コーヒー" to "koohii", "か\u3099" to "ga")
        for ((text, latin) in examples) {
            val result = parser.parse(text)
            assertEquals(latin, AsciiFormatter().format(result).latin, text)
            assertEquals(text, result.text)
            assertEquals(TextRange(0, text.length), result.spans.single().range)
        }
        val pronunciation = Pronunciation(ReadingLocale.JAPANESE, listOf(ReadingUnit("しちつふじ")))
        assertEquals("sitituhuzi", AsciiFormatter(FormatOptions(japanese = JapaneseRomanization.KUNREI)).format(pronunciation))
        val word = parser.parse("きゃっと")
        assertNull(AsciiFormatter().formatRange(word, TextRange(0, 1)))
    }
    @Test fun formattingIsAsciiAndDoesNotChangeReadings() {
        val parser = engine(); val result = parser.parse("行。？！ café🙂", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN))
        val before = result.copy()
        assertTrue(AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS)).format(result).latin.all { it.code < 128 })
        assertEquals(before, result)
        assertEquals("lv4", AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS)).format(Pronunciation(ReadingLocale.MANDARIN_CN, listOf(ReadingUnit("lv", 4)))))
    }
    @Test fun tiedSegmentationsAreRetained() {
        val lexicon = TestLexicon(ReadingLocale.MANDARIN_CN, mapOf("你好" to listOf(entry("ni3 hao3", 200)), "你" to listOf(entry("ni3", 100)), "好" to listOf(entry("hao3", 100))))
        val result = PhoneticEngine(listOf(LexiconResolver(lexicon))).parse("你好", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN, ambiguityCostWindow = 0))
        assertTrue(result.ambiguities.any { it.alternatives.map { span -> span.range.end }.distinct().size > 1 })
        assertTrue(result.spans.all { it.resolution == Resolution.AMBIGUOUS })
        assertEquals("_", AsciiFormatter(FormatOptions(requireResolved = true)).format(result).latin)
    }
    @Test fun failedParseReleasesWorkspace() {
        val workspace = ParseWorkspace()
        val failing = object : LanguageResolver {
            override val locale = ReadingLocale.MANDARIN_CN
            override fun resolve(text: String, range: TextRange, options: ParseOptions, workspace: ParseWorkspace): LanguageResult = error("test")
        }
        assertFails { PhoneticEngine(listOf(failing)).parse("行", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN), workspace) }
        assertFalse(workspace.inUse)
        assertTrue(engine().parse("行", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN), workspace).spans.isNotEmpty())
    }
    @Test fun sharedStorageIsLazyAndOwned() {
        var loads = 0
        val bytes = byteArrayOf(1, 2, 3)
        val owned = ArrayByteSource(bytes)
        bytes[0] = 9
        assertEquals(1, owned.byteAt(0))
        val loader = SharedPackLoader(PackLoader { loads++; owned })
        assertEquals(0, loads)
        assertSame(loader.load("zh-CN.lpd"), loader.load("zh-CN.lpd"))
        assertEquals(1, loads)
        assertFails { loader.load("unregistered.lpd") }
    }
}
