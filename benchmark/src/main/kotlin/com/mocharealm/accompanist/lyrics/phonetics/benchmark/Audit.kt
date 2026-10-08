package com.mocharealm.accompanist.lyrics.phonetics.benchmark

import com.mocharealm.accompanist.lyrics.phonetics.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Full-context predictions for external, local-only annotated corpora; never a release input. */
fun auditJapanese(input: File, output: File) {
    val engine = defaultEngine()
    val options = ParseOptions(defaultLocale = ReadingLocale.JAPANESE)
    val uncached = ParseWorkspace(0)
    val cached = ParseWorkspace(256)
    val formatter = AsciiFormatter()
    output.parentFile?.mkdirs()
    var count = 0
    output.bufferedWriter().use { writer ->
        input.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val fields = line.split('\t', limit = 2)
                require(fields.size == 2)
                val (id, text) = fields
                val result = engine.parse(text, options, uncached)
                check(result == engine.parse(text, options, cached)) { "Cache changed $id" }
                check(result == engine.parse(text, options, cached)) { "Warm cache changed $id" }
                val spans = result.spans.joinToString(",") { span ->
                    val candidates = span.candidates.joinToString(",") { candidate ->
                        """{"kana":${quote(candidate.pronunciation.units.joinToString("") { it.value })},"costDelta":${candidate.costDelta},"uncertaintyCostDelta":${candidate.uncertaintyCostDelta},"rules":[${candidate.selectionRules.joinToString(",", transform = ::quote)}]}"""
                    }
                    val kana = span.selected?.pronunciation?.units?.joinToString("") { it.value }
                    """{"start":${span.range.start},"end":${span.range.end},"resolution":${quote(span.resolution.name)},"kana":${kana?.let(::quote) ?: "null"},"candidates":[$candidates]}"""
                }
                val latin = formatter.format(result).latin
                // Diagnose formatter sensitivity to lexicon segmentation separately
                // from reading selection, using exactly the same selected kana.
                val joined = ArrayList<PhoneticSpan>()
                for (span in result.spans) {
                    val previous = joined.lastOrNull()
                    val previousReading = previous?.selected?.pronunciation
                    val currentReading = span.selected?.pronunciation
                    if (previous?.locale == ReadingLocale.JAPANESE && span.locale == previous.locale &&
                        previous.range.end == span.range.start && previousReading != null && currentReading != null) {
                        val reading = Pronunciation(ReadingLocale.JAPANESE,
                            previousReading.units + currentReading.units)
                        joined[joined.lastIndex] = previous.copy(range = TextRange(previous.range.start, span.range.end),
                            selected = ReadingCandidate(reading, emptyList()))
                    } else joined.add(span)
                }
                val joinedLatin = formatter.format(result.copy(spans = joined)).latin
                check(latin.all { it.code < 128 }) { "Non-ASCII output: $id" }
                writer.appendLine("""{"id":${quote(id)},"latin":${quote(latin)},"joinedLatin":${quote(joinedLatin)},"spans":[$spans],"ambiguities":${result.ambiguities.size}}""")
                count++
                if (count % 500 == 0) println("Japanese corpus: $count sentences checked")
            }
        }
    }
    println("Japanese corpus: $count sentences; cache 0/256 and warm results identical; ASCII checked.")
}

private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

/** Diagnostic/regression input is never discovered by the language data compiler. */
fun audit(input: File, output: File) {
    val loader = SharedPackLoader(ClasspathPackLoader())
    val engine = defaultEngine(loader)
    val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
    val records = ArrayList<String>()
    val dictionaries = mutableMapOf<ReadingLocale, CompiledDictionary>()
    val counts = linkedMapOf<String, Pair<Int, Int>>()
    val role = when {
        input.readLines().any { it.startsWith("# role=held-out;") } -> "held-out"
        input.readLines().any { it.startsWith("# role=regression-only;") } -> "regression-only"
        else -> "validation-only"
    }
    for (line in input.readLines().filter { it.isNotBlank() && !it.startsWith('#') }) {
        val fields = line.split('\t'); require(fields.size in 4..5)
        val (id, tag, text, expected) = fields
        val locale = requireNotNull(ReadingLocale.fromLanguageTag(tag))
        val hints = fields.getOrNull(4)?.split(',')?.map { value ->
            val parts = value.split(':'); require(parts.size == 3)
            LanguageHint(TextRange(parts[0].toInt(), parts[1].toInt()), parts[2])
        }.orEmpty()
        val options = ParseOptions(defaultLocale = locale, hints = hints)
        val parsed = engine.parse(text, options, ParseWorkspace(0))
        val workspace = ParseWorkspace(256)
        repeat(2) { check(parsed == engine.parse(text, options, workspace)) { "Cache changed $id" } }
        val actual = formatter.format(parsed).latin
        val wordEntries = dictionaries.getOrPut(locale) { CompiledDictionary(loader.load(locale.packName)) }
            .lookup(text, ParseWorkspace(0)).joinToString(",") { entry ->
                """{"reading":${quote(entry.reading)},"cost":${entry.cost},"sources":[${entry.sourceIds.joinToString(",", transform = ::quote)}]}"""
            }
        val correct = expected.split("||").any { normalized(it) == normalized(actual) }
        val (pass, total) = counts[tag] ?: (0 to 0)
        counts[tag] = (pass + if (correct) 1 else 0) to total + 1
        val spans = parsed.spans.joinToString(",") { span ->
            val candidates = span.candidates.joinToString(",") { candidate ->
                """{"reading":${quote(formatter.format(candidate.pronunciation))},"costDelta":${candidate.costDelta},"uncertaintyCostDelta":${candidate.uncertaintyCostDelta},"rules":[${candidate.selectionRules.joinToString(",", transform = ::quote)}],"sources":[${candidate.sourceIds.joinToString(",", transform = ::quote)}]}"""
            }
            """{"start":${span.range.start},"end":${span.range.end},"text":${quote(span.range.textIn(text))},"resolution":${quote(span.resolution.name)},"selected":${span.selected?.let { quote(formatter.format(it.pronunciation)) } ?: "null"},"candidates":[$candidates]}"""
        }
        records.add("""{"id":${quote(id)},"locale":${quote(tag)},"text":${quote(text)},"expected":${quote(expected)},"actual":${quote(actual)},"correct":$correct,"wordEntries":[$wordEntries],"spans":[$spans]}""")
        if (!correct) println("$tag $text: expected $expected; got $actual")
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val packs = ReadingLocale.entries.filter { it.languageTag in counts }.joinToString(",") { locale ->
        val source = loader.load(locale.packName)
        """${quote(locale.languageTag)}:{"bytes":${source.size},"sha256":${quote(sha(ByteArray(source.size) { source.byteAt(it).toByte() }))}}"""
    }
    val summary = counts.entries.joinToString(",") { (tag, value) -> """${quote(tag)}:{"correct":${value.first},"total":${value.second}}""" }
    val stamp = java.util.Properties().apply {
        requireNotNull(AccuracyCase::class.java.getResourceAsStream("/evaluation.properties")).use { load(it) }
    }
    output.parentFile?.mkdirs()
    val note = if (role == "held-out") "Original lyrics-style sentences self-annotated and frozen before this iteration's predictions. No fixes from these outputs. Not real-song accuracy or independent human blind annotation. Full cache-off/reused/warm results checked."
        else "Targeted regression/validation cases may overlap owned additions. Not independent or representative lyrics accuracy. Full cache-off/reused/warm results checked."
    output.writeText("""{"timestamp":${quote(Instant.now().toString())},"libraryVersion":${quote(stamp.getProperty("libraryVersion"))},"strategySourcesSha256":${quote(stamp.getProperty("strategySourcesSha256"))},"role":${quote(role)},"note":${quote(note)},"inputSha256":${quote(sha(input.readBytes()))},"packs":{$packs},"summary":{$summary},"cases":[${records.joinToString(",\n")}]}""" + "\n")
    counts.forEach { (tag, count) -> println("$tag: ${count.first}/${count.second}; report: ${output.absolutePath}") }
}
