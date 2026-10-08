package com.mocharealm.accompanist.lyrics.phonetics.compiler

import com.mocharealm.accompanist.lyrics.phonetics.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class InputRecord(val surface: String, val reading: String, val cost: Int, val left: Int, val right: Int, val aligned: Boolean, val source: String)
data class Matrix(val rightSize: Int, val leftSize: Int, val costs: ShortArray)
private class Writer {
    val stream = ByteArrayOutputStream()
    val size: Int get() = stream.size()
    fun u16(v: Int) { stream.write(v and 255); stream.write((v ushr 8) and 255) }
    fun i32(v: Int) { u16(v); u16(v ushr 16) }
    fun variable(v: Int) {
        require(v >= 0)
        var value = v
        while (value >= 128) { stream.write((value and 127) or 128); value = value ushr 7 }
        stream.write(value)
    }
    fun bytes(): ByteArray = stream.toByteArray()
}
private data class VariantKey(val reading: String, val left: Int, val right: Int, val aligned: Boolean)

/** Deterministic compiler; no test/validation directory discovery or runtime indexing. */
fun compile(locale: ReadingLocale, input: List<InputRecord>, matrix: Matrix? = null): ByteArray {
    require(input.isNotEmpty())
    // A source may only enter packs that actually retain its license and notices.
    val allowed = when (locale) {
        ReadingLocale.MANDARIN_CN -> setOf("unihan-17", "cedpane", "accompanist-common-polyphones")
        ReadingLocale.MANDARIN_TW -> setOf("unihan-17", "mcbopomofo", "accompanist-common-polyphones")
        ReadingLocale.CANTONESE_HK -> setOf("unihan-17", "rime-cantonese")
        ReadingLocale.JAPANESE -> setOf("ipadic", "accompanist-common-polyphones")
    }
    input.forEach { row ->
        require(row.source in allowed) { "Unreviewed data source: ${row.source}" }
        require(row.surface.isNotEmpty() && row.surface.length <= 4096 && row.reading.isNotEmpty())
        require(row.left in 0..65535 && row.right in 0..65535)
        require(!row.surface.any { it in "\t\r\n" })
        if (locale == ReadingLocale.JAPANESE) {
            require(row.reading.all { it in 'ぁ'..'ゖ' || it in "ーゝゞ" })
            require(matrix != null && row.left < matrix.leftSize && row.right < matrix.rightSize)
        } else {
            val syllables = row.reading.split(' ')
            val pattern = if (locale == ReadingLocale.CANTONESE_HK) Regex("[a-z]+[1-6]") else Regex("[a-z^]+[1-5]")
            require(syllables.all { pattern.matches(it) }) { "Invalid reading: $row" }
            require(!row.aligned || row.surface.codePointCount(0, row.surface.length) == syllables.size)
            require(row.left == 0 && row.right == 0 && matrix == null)
        }
    }
    if (matrix != null) require(matrix.costs.size.toLong() == matrix.rightSize.toLong() * matrix.leftSize)
    val groups = input.groupBy { it.surface }.toSortedMap()
    val keys = Writer(); val index = Writer(); val records = Writer(); val strings = Writer()
    val pool = HashMap<String, Int>()
    fun pooled(value: String): Int = pool.getOrPut(value) {
        val offset = strings.size
        val utf8 = value.toByteArray(Charsets.UTF_8)
        strings.variable(utf8.size); strings.stream.write(utf8)
        offset
    }
    var previous = ""; var variantCount = 0
    groups.entries.forEachIndexed { number, (surface, rows) ->
        if (number % 32 == 0) { index.i32(keys.size); previous = "" }
        var prefix = 0
        while (prefix < minOf(surface.length, previous.length) && surface[prefix] == previous[prefix]) prefix++
        keys.variable(prefix); keys.variable(surface.length - prefix)
        for (i in prefix until surface.length) keys.u16(surface[i].code)
        val variants = rows.groupBy { VariantKey(it.reading, it.left, it.right, it.aligned) }.entries
            .sortedWith(compareBy({ it.key.reading }, { it.key.left }, { it.key.right }, { it.key.aligned }))
        keys.variable(variants.size); keys.variable(variantCount)
        variants.forEach { (key, candidates) ->
            records.i32(pooled(key.reading)); records.i32(pooled(candidates.map { it.source }.distinct().sorted().joinToString(",")))
            records.i32(candidates.minOf { it.cost }); records.u16(key.left); records.u16(key.right); records.i32(if (key.aligned) 1 else 0)
            variantCount++
        }
        previous = surface
    }
    val indexOffset = 64; val keysOffset = indexOffset + index.size
    val recordOffset = keysOffset + keys.size; val stringOffset = recordOffset + records.size
    val matrixOffset = stringOffset + strings.size
    val matrixBytes = Writer()
    matrix?.costs?.forEach { matrixBytes.u16(it.toInt()) }
    val header = Writer()
    listOf(0x3144504C, 1, locale.ordinal, groups.size, variantCount, groups.keys.maxOf { it.length },
        (groups.size + 31) / 32, indexOffset, keysOffset, recordOffset, stringOffset, matrixOffset,
        matrix?.rightSize ?: 0, matrix?.leftSize ?: 0, matrixOffset + matrixBytes.size, 0).forEach(header::i32)
    return header.bytes() + index.bytes() + keys.bytes() + records.bytes() + strings.bytes() + matrixBytes.bytes()
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun verifiedInputs(root: File, manifest: File): Map<String, File> = manifest.readLines().filter { it.isNotBlank() && !it.startsWith('#') }.associate { line ->
    val fields = line.split('\t')
    require(fields.size == 4 && fields[3] == "redistribute") { "Non-release data is prohibited: $line" }
    val file = File(root, fields[1]).canonicalFile
    require(file.toPath().startsWith(File(root, "data/prepared").canonicalFile.toPath())) { "Only explicitly prepared release data is accepted" }
    require(file.isFile && sha256(file.readBytes()) == fields[2]) { "Input changed without review: ${fields[1]}" }
    fields[0] to file
}
fun readMatrix(file: File): Matrix {
    file.bufferedReader().use { reader ->
        val shape = reader.readLine().trim().split(Regex("\\s+")).map(String::toInt)
        require(shape.size == 2 && shape.all { it in 1..65535 })
        val costs = ShortArray(Math.multiplyExact(shape[0], shape[1]))
        val seen = BooleanArray(costs.size)
        reader.lineSequence().forEach { line ->
            val (right, left, cost) = line.trim().split(Regex("\\s+")).map(String::toInt)
            require(right in 0 until shape[0] && left in 0 until shape[1] && cost in Short.MIN_VALUE..Short.MAX_VALUE)
            val at = right * shape[1] + left
            require(!seen[at]); seen[at] = true; costs[at] = cost.toShort()
        }
        require(seen.all { it }) { "Incomplete matrix" }
        return Matrix(shape[0], shape[1], costs)
    }
}
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: data-compiler <repository-root>" }
    val root = File(args[0]).canonicalFile
    val inputs = verifiedInputs(root, File(root, "data/release-inputs.tsv"))
    val report = ArrayList<String>()
    ReadingLocale.entries.forEach { locale ->
        val file = inputs.getValue(locale.languageTag)
        val rows = file.readLines().map { line ->
            val f = line.split('\t'); require(f.size == 7)
            InputRecord(f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5] == "1", f[6])
        }
        val matrix = if (locale == ReadingLocale.JAPANESE) readMatrix(inputs.getValue("ja-matrix")) else null
        val result = compile(locale, rows, matrix)
        val module = when (locale) {
            ReadingLocale.MANDARIN_CN, ReadingLocale.MANDARIN_TW -> "data-mandarin"
            ReadingLocale.CANTONESE_HK -> "data-cantonese"
            ReadingLocale.JAPANESE -> "data-japanese"
        }
        val out = File(root, "$module/packs/${locale.packName}"); out.parentFile.mkdirs(); out.writeBytes(result)
        val dictionary = CompiledDictionary(ArrayByteSource(result))
        report += "${locale.languageTag}\t${dictionary.surfaceCount}\t${dictionary.variantCount}\t${result.size}\t${sha256(result)}"
        println(report.last())
    }
    File(root, "data/compiled-packs.tsv").writeText("# locale\tsurfaces\tvariants\tbytes\tsha256\n" + report.joinToString("\n") + "\n")
}
