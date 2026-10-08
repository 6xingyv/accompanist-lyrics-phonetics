package com.mocharealm.accompanist.lyrics.phonetics.benchmark

import com.mocharealm.accompanist.lyrics.phonetics.*
import java.util.concurrent.Executors
import kotlin.test.*

class PackIntegrationTest {
    @Test fun grammaticalMetadataPreservesComparisonsAndWeakEvidence() {
        val engine = defaultEngine(); val options = ParseOptions(defaultLocale = ReadingLocale.JAPANESE)
        val formatter = AsciiFormatter()
        for (text in listOf("高い方だ", "思う方が多い", "書く方には")) {
            val result = engine.parse(text, options)
            assertEquals("ほう", result.spans.single { it.range.textIn(text) == "方" }.selected!!.pronunciation.units.single().value, text)
            assertEquals(result, engine.parse(text, options, ParseWorkspace(0)))
        }
        val person = engine.parse("学生の方", options).spans.last()
        assertEquals(Resolution.AMBIGUOUS, person.resolution)
        assertTrue(person.candidates.any { formatter.format(it.pronunciation) == "hou" && it.uncertaintyCostDelta == 0L && it.costDelta > 100 })
        assertTrue(person.selected!!.selectionRules.isNotEmpty())
        assertEquals(Resolution.AMBIGUOUS, engine.parse("云った", options).spans.first().resolution)
        val interval = engine.parse("泳ぐ間", options).spans.last()
        assertEquals("aida", formatter.format(interval.selected!!.pronunciation))
        assertTrue("ja-attributive-interval" in interval.selected!!.selectionRules)
        assertEquals(Resolution.RESOLVED, interval.resolution)
    }
    @Test fun japaneseGrammarPreferencesKeepContrastingMeaningsAndCachedEntries() {
        val engine = defaultEngine()
        val options = ParseOptions(defaultLocale = ReadingLocale.JAPANESE)
        val workspace = ParseWorkspace(256)
        val formatter = AsciiFormatter()
        for ((text, expected) in listOf("学生の方" to "gakusei no kata", "右の方" to "migi no hou",
            "小さい方がいい" to "chiisai hou ga ii", "安い方を選ぶ" to "yasui hou o erabu",
            "学生の方が多い" to "gakusei no hou ga ooi",
            "長い間" to "nagai aida", "君との間" to "kimi to no aida", "床の間" to "tokonoma",
            "茶の間" to "chanoma", "間に合う" to "maniau", "何の本" to "nan no hon",
            "何だ" to "nan da", "何か" to "nanika", "何を" to "nani o", "何と何" to "nani to nani",
            "云った" to "itta", "云々" to "unnun", "18日" to "juuhachinichi", "3人" to "sannin",
            "３名" to "sanmei", "12回" to "juunikai", "5秒" to "gobyou")) {
            val result = engine.parse(text, options, ParseWorkspace(0))
            assertEquals(normalized(expected), normalized(formatter.format(result).latin), text)
            repeat(2) { assertEquals(result, engine.parse(text, options, workspace), text) }
            assertEquals(text, result.spans.joinToString("") { it.range.textIn(text) }, text)
        }
        val person = engine.parse("学生の方", options).spans.last()
        assertTrue(person.candidates.any { it.pronunciation.units.single().value == "ほう" })
        assertTrue(engine.parse("長い間", options).spans.last().candidates.any { it.pronunciation.units.single().value == "ま" })
        assertEquals("hou", formatter.format(engine.parse("方", options)).latin)
        assertEquals("nani", formatter.format(engine.parse("何", options)).latin)
    }
    @Test fun japaneseCalendarWordsAndPersonCompoundsKeepReadingsSourcesAndAmbiguity() {
        val engine = defaultEngine()
        val options = ParseOptions(defaultLocale = ReadingLocale.JAPANESE)
        val formatter = AsciiFormatter()
        val workspace = ParseWorkspace(256)
        for ((text, expected) in listOf("一人" to "hitori", "二人" to "futari",
            "一人称" to "ichininshou", "一人前" to "ichininmae", "二人三脚" to "nininsankyaku",
            "十二人" to "juuninin", "二十一人" to "nijuuichinin", "二十二日" to "nijuuninichi",
            "五月雨" to "samidare", "三日月" to "mikazuki", "二日酔い" to "futsukayoi",
            "4年次生" to "yonenjisei", "5年度" to "gonendo", "3歳児" to "sansaiji",
            "四月" to "shigatsu", "七月" to "shichigatsu", "九月" to "kugatsu",
            "二日" to "futsuka", "十四日" to "juuyokka", "二十日" to "hatsuka", "二十四日" to "nijuuyokka",
            "月" to "tsuki", "年" to "toshi", "歳" to "toshi", "と言った。" to "to itta.")) {
            val result = engine.parse(text, options, ParseWorkspace(0))
            assertEquals(normalized(expected), normalized(formatter.format(result).latin), text)
            repeat(2) { assertEquals(result, engine.parse(text, options, workspace), text) }
        }
        val day = engine.parse("一日", options).spans.single()
        assertEquals(Resolution.AMBIGUOUS, day.resolution)
        assertEquals(setOf("いちにち", "ついたち"), day.candidates.filter { it.costDelta == 0L }
            .map { it.pronunciation.units.single().value }.toSet())
        assertTrue(day.candidates.any { it.pronunciation.units.single().value == "ひといち" && "ipadic" in it.sourceIds })
        assertNull(formatter.formatRange(engine.parse("二日", options), TextRange(0, 1)))
        val person = engine.parse("一人", options).spans.single()
        assertTrue(person.candidates.any { it.pronunciation.units.single().value == "かずと" && "ipadic" in it.sourceIds })
        for ((text, expected) in listOf("５年" to "ごねん", "4月" to "しがつ", "９歳" to "きゅうさい", "7時" to "しちじ")) {
            val result = engine.parse(text, options, workspace)
            assertEquals(expected, result.spans.last().selected!!.pronunciation.units.single().value, text)
            assertTrue(result.spans.last().selected!!.sourceIds.contains("accompanist-japanese-numbers"), text)
            assertEquals(result, engine.parse(text, options, ParseWorkspace(0)))
        }
        val source = javaClass.classLoader.getResource("phonetics-notices/data-japanese/sources.json")!!.readText()
        assertTrue(source.contains("accompanist-common-polyphones"))
    }
    @Test fun fixedCorpusAndEveryCacheCapacity() {
        val engine = defaultEngine(); val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
        for (case in corpus()) {
            val options = ParseOptions(defaultLocale = case.locale)
            val baseline = engine.parse(case.text, options, ParseWorkspace(0))
            assertEquals(normalized(case.expected), normalized(formatter.format(baseline).latin), case.id)
            for (capacity in listOf(1, 4, 256)) {
                val workspace = ParseWorkspace(capacity)
                repeat(2) { assertEquals(baseline, engine.parse(case.text, options, workspace), "${case.id}, cache=$capacity") }
            }
        }
    }
    @Test fun sharedEngineWithIndependentWorkspaces() {
        val engine = defaultEngine()
        val cases = corpus()
        val expected = cases.map { engine.parse(it.text, ParseOptions(defaultLocale = it.locale)) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..15).map { executor.submit<List<ParseResult>> {
                val workspace = ParseWorkspace()
                cases.map { engine.parse(it.text, ParseOptions(defaultLocale = it.locale), workspace) }
            } }
            jobs.forEach { assertEquals(expected, it.get()) }
        } finally { executor.shutdownNow() }
    }
    @Test fun noticesArePresentAndHanAmbiguityPreserved() {
        val resource = javaClass.classLoader
        for (path in listOf("data-mandarin/unicode/LICENSE", "data-mandarin/cedpane/LICENSE", "data-mandarin/cedpane/README.md",
            "data-mandarin/mcbopomofo/LICENSE", "data-mandarin/libtabe/COPYING",
            "data-cantonese/rime-cantonese/LICENSE", "data-japanese/ipadic/COPYING"))
            assertNotNull(resource.getResource("phonetics-notices/$path"), path)
        val result = defaultEngine().parse("行")
        assertEquals(Resolution.LANGUAGE_AMBIGUOUS, result.spans.single().resolution)
        assertEquals(4, result.languageAlternatives.size)
    }
    @Test fun admittedMandarinWordsRetainSourcesOffsetsAndAmbiguity() {
        val engine = defaultEngine()
        fun parse(text: String, locale: ReadingLocale, capacity: Int = 0) =
            engine.parse(text, ParseOptions(defaultLocale = locale), ParseWorkspace(capacity))
        val cp = parse("🎵沃兹沃思!", ReadingLocale.MANDARIN_CN)
        val name = cp.spans.single { "cedpane" in it.selected?.sourceIds.orEmpty() }
        assertEquals(TextRange(2, 6), name.range)
        assertEquals("沃兹沃思", name.range.textIn(cp.text))
        val nameReading = assertNotNull(name.selected).pronunciation
        assertEquals(listOf(TextRange(2, 3), TextRange(3, 4), TextRange(4, 5), TextRange(5, 6)),
            nameReading.units.map { it.sourceRange })
        assertEquals("zi", nameReading.units[1].value)
        val tw = parse("樂器", ReadingLocale.MANDARIN_TW)
        assertEquals(listOf("accompanist-common-polyphones", "mcbopomofo"), tw.spans.single().selected!!.sourceIds)
        assertEquals("yue4 qi4", AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS)).format(tw).latin)
        val alternatives = parse("行行", ReadingLocale.MANDARIN_TW)
        assertEquals(Resolution.AMBIGUOUS, alternatives.spans.single().resolution)
        assertEquals(setOf("hang", "xing"), alternatives.spans.single().candidates.map { it.pronunciation.units.first().value }.toSet())
        val sandhi = parse("不一樣", ReadingLocale.MANDARIN_TW)
        assertEquals("bu4 yi1 yang4", AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS)).format(sandhi).latin)
        for ((text, locale) in listOf("🎵沃兹沃思!" to ReadingLocale.MANDARIN_CN, "樂器" to ReadingLocale.MANDARIN_TW,
            "行行" to ReadingLocale.MANDARIN_TW, "不一樣" to ReadingLocale.MANDARIN_TW)) {
            assertEquals(parse(text, locale), parse(text, locale, 256), text)
        }
        assertTrue(parse("樂器", ReadingLocale.MANDARIN_CN).spans.none { "mcbopomofo" in it.selected?.sourceIds.orEmpty() })
        assertTrue(parse("沃兹沃思", ReadingLocale.MANDARIN_TW).spans.none { "cedpane" in it.selected?.sourceIds.orEmpty() })
    }
    @Test fun commonPolyphonesRegressionsAndCacheInvariance() {
        val engine = defaultEngine()
        val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
        val lines = javaClass.classLoader.getResourceAsStream("mandarin-polyphones.tsv")!!.bufferedReader().use { it.readLines() }
        var count = 0
        for (line in lines.filter { it.isNotBlank() && !it.startsWith('#') }) {
            val (id, tag, text, expected) = line.split('\t')
            val options = ParseOptions(defaultLocale = ReadingLocale.fromLanguageTag(tag))
            val result = engine.parse(text, options, ParseWorkspace(0))
            assertEquals(expected, formatter.format(result).latin, id)
            assertTrue(result.spans.any { "accompanist-common-polyphones" in it.selected?.sourceIds.orEmpty() }, id)
            val workspace = ParseWorkspace(256)
            repeat(2) { assertEquals(result, engine.parse(text, options, workspace), id) }
            count++
        }
        assertEquals(195, count)
    }
    @Test fun ownSourceKeepsOffsetsAndUnresolvedReadings() {
        val engine = defaultEngine()
        val cn = ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)
        val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
        val result = engine.parse("🎵着迷!", cn)
        val word = result.spans.single { "accompanist-common-polyphones" in it.selected?.sourceIds.orEmpty() }
        assertEquals(TextRange(2, 4), word.range)
        assertEquals(listOf(TextRange(2, 3), TextRange(3, 4)), word.selected!!.pronunciation.units.map { it.sourceRange })
        assertEquals("zhao2 mi2", formatter.format(word.selected!!.pronunciation))
        val isolated = engine.parse("着", cn).spans.single()
        assertEquals(Resolution.AMBIGUOUS, isolated.resolution)
        assertTrue(isolated.candidates.size > 1)
        assertTrue(isolated.candidates.all { "accompanist-common-polyphones" !in it.sourceIds })
        val tw = engine.parse("著涼", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_TW)).spans.single()
        assertTrue(tw.candidates.any { formatter.format(it.pronunciation) == "zhao2 liang2" })
        val repeated = engine.parse("重重", cn)
        assertEquals(Resolution.AMBIGUOUS, repeated.spans.single().resolution)
        assertEquals(setOf("chong2 chong2", "zhong4 zhong4"),
            repeated.spans.single().candidates.filter { it.costDelta == 0L }.map { formatter.format(it.pronunciation) }.toSet())
        assertEquals(repeated, engine.parse("重重", cn, ParseWorkspace(256)))
        for ((locale, expected) in listOf(ReadingLocale.MANDARIN_CN to "la1 ji1", ReadingLocale.MANDARIN_TW to "le4 se4")) {
            val regional = engine.parse("垃圾", ParseOptions(defaultLocale = locale))
            assertEquals(expected, formatter.format(regional).latin)
            assertTrue("accompanist-common-polyphones" in regional.spans.single().selected!!.sourceIds)
        }
        for (path in listOf("sources.json", "LICENSE", "NOTICE")) {
            assertNotNull(javaClass.classLoader.getResource("phonetics-notices/data-mandarin/$path"))
        }
        assertNull(javaClass.classLoader.getResource("phonetics-notices/data-mandarin/curated/context.tsv"))
    }
}
