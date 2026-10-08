package com.mocharealm.accompanist.lyrics.phonetics.benchmark

import com.mocharealm.accompanist.lyrics.phonetics.*
import com.mocharealm.accompanist.lyrics.phonetics.data.*
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import java.security.MessageDigest
import kotlin.system.measureNanoTime

data class AccuracyCase(val id: String, val locale: ReadingLocale, val text: String, val expected: String)
fun corpus(): List<AccuracyCase> = requireNotNull(AccuracyCase::class.java.getResourceAsStream("/accuracy.tsv")).bufferedReader().use { reader ->
    reader.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
        val fields = line.split('\t'); require(fields.size == 4)
        AccuracyCase(fields[0], requireNotNull(ReadingLocale.fromLanguageTag(fields[1])), fields[2], fields[3])
    }
}
fun defaultEngine(loader: PackLoader = ClasspathPackLoader()) = PhoneticEngine(listOf(
    MandarinData.mainland(loader), MandarinData.taiwan(loader), CantoneseData.hongKong(loader), JapaneseData.japanese(loader),
))
fun normalized(text: String): String = text.filterNot(Char::isWhitespace)
private fun digest(source: ByteSource): String {
    val hash = MessageDigest.getInstance("SHA-256")
    val chunk = ByteArray(65536)
    var offset = 0
    while (offset < source.size) {
        val count = minOf(chunk.size, source.size - offset)
        for (i in 0 until count) chunk[i] = source.byteAt(offset + i).toByte()
        hash.update(chunk, 0, count); offset += count
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--japanese-audit") {
        require(args.size == 3) { "Usage: --japanese-audit <id-text-tsv> <predictions-jsonl>" }
        auditJapanese(File(args[1]), File(args[2]))
        return
    }
    if (args.firstOrNull() == "--audit") {
        require(args.size == 3) { "Usage: --audit <validation-tsv> <report-json>" }
        audit(File(args[1]), File(args[2]))
        return
    }
    if (args.firstOrNull() == "--inspect") {
        require(args.size >= 3) { "Usage: --inspect <language-tag> <text> ..." }
        val locale = requireNotNull(ReadingLocale.fromLanguageTag(args[1]))
        val engine = defaultEngine()
        val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
        for (text in args.drop(2)) {
            val parsed = engine.parse(text, ParseOptions(defaultLocale = locale), ParseWorkspace(0))
            println("${locale.languageTag}: $text -> ${formatter.format(parsed).latin}")
            for (span in parsed.spans) {
                println("  ${span.range}: ${span.range.textIn(text)} ${span.resolution}")
                for (candidate in span.candidates) println("    ${if (candidate == span.selected) "*" else " "} ${formatter.format(candidate.pronunciation)} delta=${candidate.costDelta} sources=${candidate.sourceIds}")
            }
        }
        return
    }
    val output = File(args.firstOrNull() ?: "build/benchmark-jvm.json")
    val cases = corpus()
    val runtime = Runtime.getRuntime()
    fun heap() = runtime.totalMemory() - runtime.freeMemory()
    val baselineHeap = heap()
    val loader: PackLoader = if (args.size > 1) JvmFilePackLoader(File(args[1]).toPath()) else ClasspathPackLoader()
    val engine = defaultEngine(loader)
    val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
    val workspace = ParseWorkspace()
    val first = cases.first()
    val firstNanos = measureNanoTime { engine.parse(first.text, ParseOptions(defaultLocale = first.locale), workspace) }
    val failures = ArrayList<String>()
    val metrics = ArrayList<String>()
    for (locale in ReadingLocale.entries) {
        val selected = cases.filter { it.locale == locale }
        val separate = defaultEngine(loader)
        val coldNanos = measureNanoTime { separate.parse(selected.first().text, ParseOptions(defaultLocale = locale), ParseWorkspace(0)) }
        var correct = 0
        for (case in selected) {
            val options = ParseOptions(defaultLocale = locale)
            val uncached = engine.parse(case.text, options, ParseWorkspace(0))
            val cached = engine.parse(case.text, options, workspace)
            check(uncached == cached) { "Cache changed result: ${case.id}" }
            val latin = formatter.format(cached).latin
            if (normalized(latin) == normalized(case.expected)) correct++
            else failures.add("${case.id}: expected ${case.expected}; got $latin")
        }
        repeat(50) { for (case in selected) engine.parse(case.text, ParseOptions(defaultLocale = locale), workspace) }
        val samples = LongArray(300) { i ->
            val case = selected[i % selected.size]
            measureNanoTime { engine.parse(case.text, ParseOptions(defaultLocale = locale), workspace) }
        }.sorted()
        val data = loader.load(locale.packName)
        metrics.add("""{"locale":"${locale.languageTag}","packBytes":${data.size},"packSha256":"${digest(data)}","processFirstParseNs":$coldNanos,"hotMedianNs":${samples[150]},"hotP95Ns":${samples[285]},"correct":$correct,"total":${selected.size}}""")
    }
    val observedHeap = heap()
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
    val peakHeap = pools.sumOf { it.peakUsage.used }
    fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    val report = """{
  "timestamp":${quoted(Instant.now().toString())},
  "platform":${quoted(System.getProperty("os.name") + " " + System.getProperty("os.arch"))},
  "java":${quoted(System.getProperty("java.runtime.version"))},
  "storage":${quoted(if (args.size > 1) "mmap" else "classpath")},
  "corpusSha256":${quoted(MessageDigest.getInstance("SHA-256").digest(requireNotNull(AccuracyCase::class.java.getResourceAsStream("/accuracy.tsv")).use { it.readBytes() }).joinToString("") { "%02x".format(it) })},
  "firstParseNs":$firstNanos,
  "heapBefore":$baselineHeap,"heapAfterWorkload":$observedHeap,"peakHeapObserved":$peakHeap,
  "memoryNote":"JVM heap observations include results and GC effects; not RSS or per-pack retained memory. Cold parses here may share warmed OS page cache. Use fresh processes/device tools for release comparisons.",
  "metrics":[${metrics.joinToString(",")}],
  "failures":[${failures.joinToString(",", transform = ::quoted)}]
}
"""
    output.parentFile?.mkdirs(); output.writeText(report)
    println(report)
    check(failures.isEmpty()) { "Fixed corpus failed; report: ${output.absolutePath}" }
}
