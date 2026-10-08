package com.mocharealm.accompanist.lyrics.phonetics.devicebenchmark

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Debug
import com.mocharealm.accompanist.lyrics.phonetics.*
import com.mocharealm.accompanist.lyrics.phonetics.data.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** No Activity, UI, account access or sample application state is involved. */
class PhoneticsInstrumentation : Instrumentation() {
    private var cacheCapacity = 256
    private var requestedLocale: ReadingLocale? = null
    private var phaseMode = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        cacheCapacity = arguments?.getString("cache")?.toInt() ?: 256
        requestedLocale = arguments?.getString("locale")?.let { requireNotNull(ReadingLocale.fromLanguageTag(it)) }
        phaseMode = arguments?.getString("phases") == "true"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val setupStart = System.nanoTime()
            val loader = SharedPackLoader(AndroidPackLoader(targetContext))
            val engine = PhoneticEngine(listOf(MandarinData.mainland(loader), MandarinData.taiwan(loader), CantoneseData.hongKong(loader), JapaneseData.japanese(loader)))
            val setupNs = System.nanoTime() - setupStart
            val formatter = AsciiFormatter(FormatOptions(tones = ToneStyle.NUMBERS))
            val corpusBytes = targetContext.assets.open("accuracy.tsv").use { it.readBytes() }
            val corpus = corpusBytes.decodeToString().lines().filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }
            val metrics = JSONArray(); val failures = JSONArray(); val phases = JSONArray()
            fun memory(): JSONObject {
                val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                val runtime = Runtime.getRuntime()
                val status = File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
                return JSONObject().put("pssKb", info.totalPss).put("javaHeapUsed", runtime.totalMemory() - runtime.freeMemory())
                    .put("nativeHeapAllocated", Debug.getNativeHeapAllocatedSize()).put("procStatus", status.joinToString("; "))
            }
            val before = memory()
            for (locale in ReadingLocale.entries.filter { requestedLocale == null || it == requestedLocale }) {
                val cases = corpus.filter { ReadingLocale.fromLanguageTag(it[1]) == locale }
                val workspace = ParseWorkspace(cacheCapacity)
                val options = ParseOptions(defaultLocale = locale)
                if (phaseMode) {
                    val loadStart = System.nanoTime()
                    val source = loader.load(locale.packName)
                    val loadNs = System.nanoTime() - loadStart
                    val headerStart = System.nanoTime()
                    val dictionary = CompiledDictionary(source)
                    val headerNs = System.nanoTime() - headerStart
                    val parseStart = System.nanoTime()
                    val parsed = engine.parse(cases.first()[2], options, workspace)
                    val parseNs = System.nanoTime() - parseStart
                    val formatStart = System.nanoTime()
                    formatter.format(parsed)
                    phases.put(JSONObject().put("locale", locale.languageTag).put("setupNs", setupNs).put("mapNs", loadNs)
                        .put("headerNs", headerNs).put("firstParseWithMappedStorageNs", parseNs)
                        .put("formatNs", System.nanoTime() - formatStart).put("surfaceCount", dictionary.surfaceCount))
                }
                var start = System.nanoTime()
                val first = engine.parse(cases.first()[2], options, workspace)
                val coldNanos = System.nanoTime() - start
                var correct = 0; val signature = MessageDigest.getInstance("SHA-256"); var ambiguities = 0
                for (case in cases) {
                    val parsed = engine.parse(case[2], options, workspace)
                    check(parsed == engine.parse(case[2], options, ParseWorkspace(0))) { "Cache changed result: ${case[0]}" }
                    // Enum.hashCode is process-specific; compare deterministic result text instead.
                    signature.update(parsed.toString().toByteArray(Charsets.UTF_8))
                    ambiguities += parsed.ambiguities.size
                    val actual = formatter.format(parsed).latin
                    if (actual.filterNot(Char::isWhitespace) == case[3].filterNot(Char::isWhitespace)) correct++
                    else failures.put("${case[0]}: expected ${case[3]}; got $actual")
                }
                repeat(50) { for (case in cases) engine.parse(case[2], options, workspace) }
                val samples = LongArray(300) { index ->
                    start = System.nanoTime(); engine.parse(cases[index % cases.size][2], options, workspace); System.nanoTime() - start
                }.sorted()
                val descriptorLength = targetContext.assets.openFd(locale.packName).use { it.length }
                metrics.put(JSONObject().put("locale", locale.languageTag).put("packBytes", descriptorLength)
                    .put("firstParseNs", coldNanos).put("hotMedianNs", samples[150]).put("hotP95Ns", samples[285])
                    .put("correct", correct).put("total", cases.size).put("resultSignature", signature.digest().joinToString("") { "%02x".format(it) }).put("ambiguities", ambiguities)
                    .put("firstSpanCount", first.spans.size))
            }
            val report = JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.first()).put("build", Build.FINGERPRINT).put("cacheCapacity", cacheCapacity)
                .put("runtimeSourcesSha256", BuildConfig.RUNTIME_SOURCES_SHA256)
                .put("libraryVersion", BuildConfig.VERSION_NAME).put("phaseMode", phaseMode).put("phases", phases)
                .put("phaseNote", "Separate fresh-process diagnostic mode pre-maps storage and probes a header before parsing. Parse includes lazy resolver initialization and a second header read. These component times must not be summed or substituted for normal firstParseNs; class/JIT/page-cache effects remain.")
                .put("corpusSha256", MessageDigest.getInstance("SHA-256").digest(corpusBytes).joinToString("") { "%02x".format(it) })
                .put("packManifest", JSONArray().apply {
                    for (module in listOf("data-mandarin", "data-cantonese", "data-japanese")) {
                        put(JSONObject(targetContext.assets.open("phonetics-notices/$module/sources.json").bufferedReader().use { it.readText() }))
                    }
                })
                .put("timestampMs", System.currentTimeMillis()).put("memoryBefore", before).put("memoryAfter", memory())
                .put("memoryNote", "Debug build. Observed process memory includes instrumentation and workload/GC effects; not isolated pack retention. First parse is process-cold, not guaranteed filesystem-cold.")
                .put("metrics", metrics).put("failures", failures)
            val file = File(targetContext.filesDir, "phonetics-benchmark.json").also { it.writeText(report.toString(2)) }
            result.putString("stream", "${file.absolutePath}\n${report.toString(2)}\n")
            check(failures.length() == 0) { "Accuracy regression" }
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("stream", failure.stackTraceToString()); finish(0, result)
        }
    }
}
