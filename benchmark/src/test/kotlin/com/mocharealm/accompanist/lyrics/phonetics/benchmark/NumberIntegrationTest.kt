package com.mocharealm.accompanist.lyrics.phonetics.benchmark

import com.mocharealm.accompanist.lyrics.phonetics.*
import kotlin.test.*

class NumberIntegrationTest {
    @Test fun countersAndYearsRetainCompleteRangesAndCacheIndependentReadings() {
        val engine = defaultEngine()
        val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
        val cases = listOf(
            Triple(ReadingLocale.JAPANESE, "1人", "hitori"), Triple(ReadingLocale.JAPANESE, "2人", "futari"),
            Triple(ReadingLocale.JAPANESE, "4人", "yonin"), Triple(ReadingLocale.JAPANESE, "14人", "juuyonin"),
            Triple(ReadingLocale.JAPANESE, "100歳", "hyakusai"),
            Triple(ReadingLocale.JAPANESE, "2日", "futsuka"), Triple(ReadingLocale.JAPANESE, "4月1日", "shigatsu tsuitachi"),
            Triple(ReadingLocale.JAPANESE, "1分", "ippun"), Triple(ReadingLocale.JAPANESE, "3分", "sanpun"),
            Triple(ReadingLocale.JAPANESE, "6分", "roppun"), Triple(ReadingLocale.JAPANESE, "100分", "hyappun"),
            Triple(ReadingLocale.JAPANESE, "1人前", "ichininmae"), Triple(ReadingLocale.JAPANESE, "2日間", "futsukakan"),
            Triple(ReadingLocale.MANDARIN_CN, "2026年", "er4 ling2 er4 liu4 nian2"),
            Triple(ReadingLocale.MANDARIN_CN, "2人", "liang3 ren2"), Triple(ReadingLocale.MANDARIN_CN, "12人", "shi2 er4 ren2"),
            Triple(ReadingLocale.MANDARIN_CN, "10000年", "yi1 wan4 nian2"), Triple(ReadingLocale.MANDARIN_CN, "10001元", "yi1 wan4 ling2 yi1 yuan2"),
            Triple(ReadingLocale.MANDARIN_CN, "100年", "yi1 bai3 nian2"),
            Triple(ReadingLocale.MANDARIN_CN, "2.5小时", "er4 dian3 wu3 xiao3 shi2"),
            Triple(ReadingLocale.MANDARIN_CN, "123", "yi1 bai3 er4 shi2 san1"),
            Triple(ReadingLocale.MANDARIN_CN, "负2.5", "fu4 er4 dian3 wu3"),
            Triple(ReadingLocale.MANDARIN_CN, "100000001元", "yi1 yi4 ling2 yi1 yuan2"),
            Triple(ReadingLocale.MANDARIN_TW, "２０２６年", "er4 ling2 er4 liu4 nian2"),
            Triple(ReadingLocale.MANDARIN_TW, "１２３", "yi1 bai3 er4 shi2 san1"),
            Triple(ReadingLocale.MANDARIN_TW, "負2.5小時", "fu4 er4 dian3 wu3 xiao3 shi2"),
            Triple(ReadingLocale.MANDARIN_TW, "2個", "liang3 ge4"), Triple(ReadingLocale.MANDARIN_TW, "2月2日", "er4 yue4 er4 ri4"),
            Triple(ReadingLocale.CANTONESE_HK, "2026年", "ji6 ling4 ji6 luk6 nin4"),
            Triple(ReadingLocale.CANTONESE_HK, "2人", "loeng5 jan4"), Triple(ReadingLocale.CANTONESE_HK, "12人", "sap6 ji6 jan4"),
            Triple(ReadingLocale.CANTONESE_HK, "123", "jat1 baak3 ji6 sap6 saam1"),
            Triple(ReadingLocale.CANTONESE_HK, "負2.5", "fu6 ji6 dim2 ng5"),
            Triple(ReadingLocale.MANDARIN_CN, "二〇二六年", "er4 ling2 er4 liu4 nian2"),
        )
        val cached = ParseWorkspace(256)
        for ((locale, text, expected) in cases) {
            val options = ParseOptions(defaultLocale = locale)
            val parsed = engine.parse(text, options, ParseWorkspace(0))
            assertEquals(normalized(expected), normalized(formatter.format(parsed).latin), "$locale $text")
            repeat(2) { assertEquals(parsed, engine.parse(text, options, cached), text) }
            assertEquals(text, parsed.spans.joinToString("") { it.range.textIn(text) })
            assertTrue(parsed.spans.any { s -> s.selected?.sourceIds.orEmpty().any { it.endsWith("-numbers") } }, text)
        }
        val day = engine.parse("1日", ParseOptions(defaultLocale = ReadingLocale.JAPANESE)).spans.single()
        assertEquals(Resolution.AMBIGUOUS, day.resolution)
        assertEquals(setOf("いちにち", "ついたち"), day.candidates.filter { it.costDelta == 0L }.map { it.pronunciation.units.single().value }.toSet())
        val two = engine.parse("2人", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)).spans.single()
        assertEquals(Resolution.AMBIGUOUS, two.resolution)
        assertEquals(setOf("er4 ren2", "liang3 ren2"), two.candidates.map { formatter.format(it.pronunciation) }.toSet())
        val year = engine.parse("2026年", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)).spans.single()
        assertEquals(Resolution.AMBIGUOUS, year.resolution)
        assertTrue(year.candidates.any { formatter.format(it.pronunciation) == "er4 qian1 ling2 er4 shi2 liu4 nian2" })
        val written = engine.parse("一千年", ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN))
        assertEquals("yi1 qian1 nian2", formatter.format(written).latin)
        assertTrue(written.spans.none { "accompanist-chinese-numbers" in it.selected?.sourceIds.orEmpty() })
        for ((locale, text, expected) in listOf(Triple(ReadingLocale.MANDARIN_CN, "等你1000年", "yi1 qian1 nian2"),
            Triple(ReadingLocale.MANDARIN_TW, "持續1000年", "yi1 qian1 nian2"), Triple(ReadingLocale.CANTONESE_HK, "整整1000年", "jat1 cin1 nin4"))) {
            val result = engine.parse(text, ParseOptions(defaultLocale = locale))
            val number = result.spans.single { it.range.textIn(text) == "1000年" }
            assertEquals(expected, formatter.format(number.selected!!.pronunciation))
            assertEquals(Resolution.AMBIGUOUS, number.resolution)
            assertEquals(result, engine.parse(text, ParseOptions(defaultLocale = locale), ParseWorkspace(0)))
        }
        assertNull(formatter.formatRange(engine.parse("2日", ParseOptions(defaultLocale = ReadingLocale.JAPANESE)), TextRange(0, 1)))
        for ((text, readings) in listOf("7人" to setOf("ななにん", "しちにん"),
            "7年" to setOf("ななねん", "しちねん"), "20歳" to setOf("はたち", "にじゅっさい", "にじっさい"),
            "0歳" to setOf("れいさい", "ぜろさい"))) {
            val span = engine.parse(text, ParseOptions(defaultLocale = ReadingLocale.JAPANESE)).spans.single()
            assertTrue(span.candidates.map { it.pronunciation.units.single().value }.toSet().containsAll(readings), text)
        }
    }
    @Test fun languagesIdentifiersAndUnsupportedNumeralsDoNotBorrowCounterContext() {
        val engine = defaultEngine()
        for (text in listOf("A2人", "1,000人", "1.2人", "-2人", "10001人")) {
            val parsed = engine.parse(text, ParseOptions(defaultLocale = ReadingLocale.JAPANESE))
            assertTrue(parsed.spans.none { "accompanist-japanese-numbers" in it.selected?.sourceIds.orEmpty() }, text)
        }
        for (text in listOf("A2人", "1,000元", "2abc", "12/24", "-2元", "12345678901234567元")) {
            assertTrue(engine.parse(text, ParseOptions(defaultLocale = ReadingLocale.MANDARIN_CN)).spans.none {
                "accompanist-chinese-numbers" in it.selected?.sourceIds.orEmpty()
            }, text)
        }
        val hints = listOf(LanguageHint(TextRange(0, 1), "es"), LanguageHint(TextRange(1, 2), "ja"))
        assertTrue(engine.parse("2人", ParseOptions(hints = hints)).spans.none { "accompanist-japanese-numbers" in it.selected?.sourceIds.orEmpty() })
        assertTrue(engine.parse("2 años", ParseOptions(hints = listOf(LanguageHint(TextRange(0, 6), "es")))).spans.all { it.resolution == Resolution.PASSTHROUGH })
        val mixed = engine.parse("2人と3人", ParseOptions(hints = listOf(LanguageHint(TextRange(0, 2), "zh-CN"), LanguageHint(TextRange(2, 5), "ja"))))
        assertEquals("liang3ren2tosannin", normalized(AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS)).format(mixed).latin))
    }
}
