package com.mocharealm.accompanist.lyrics.phonetics.compiler

import com.mocharealm.accompanist.lyrics.phonetics.*
import java.nio.file.Files
import kotlin.test.*

class CompilerTest {
    private fun row(key: String, reading: String = "ni3") = InputRecord(key, reading, 900, 0, 0, true, "accompanist-common-polyphones")
    @Test fun deterministicDeduplicationAndBlockBoundaries() {
        val rows = (0..100).map { row((0x4E00 + it).toChar().toString()) } + listOf(row("𠀀"), row("你"), row("你"))
        val packed = compile(ReadingLocale.MANDARIN_CN, rows)
        assertContentEquals(packed, compile(ReadingLocale.MANDARIN_CN, rows.reversed()))
        var reads = 0
        val source = object : ByteSource {
            override val size = packed.size
            override fun byteAt(offset: Int): Int { reads++; return packed[offset].toInt() and 255 }
        }
        val dictionary = CompiledDictionary(source)
        assertTrue(reads <= 100, "Opening must only read header; reads=$reads")
        for (row in rows) assertEquals(listOf("ni3"), dictionary.lookup(row.surface, ParseWorkspace(0)).map { it.reading }, row.surface)
        assertTrue(dictionary.lookup("不存在", ParseWorkspace()).isEmpty())
    }
    @Test fun rejectsCorruptHeadersAndUnreviewedSources() {
        assertFailsWith<IllegalArgumentException> { compile(ReadingLocale.MANDARIN_CN, listOf(row("你").copy(source = "validation-only"))) }
        assertFailsWith<IllegalArgumentException> { compile(ReadingLocale.MANDARIN_CN, listOf(row("你").copy(source = "ipadic"))) }
        assertFailsWith<IllegalArgumentException> { CompiledDictionary(ArrayByteSource(ByteArray(64))) }
        val packed = compile(ReadingLocale.MANDARIN_CN, listOf(row("你")))
        assertFails { CompiledDictionary(ArrayByteSource(packed.copyOf(packed.size - 1))) }
        assertFails { compile(ReadingLocale.MANDARIN_CN, listOf(row("你好"))) }
    }
    @Test fun matrixOrientationAndSignedCosts() {
        val matrix = Matrix(2, 2, shortArrayOf(0, -100, 200, 300))
        val packed = compile(ReadingLocale.JAPANESE, listOf(InputRecord("歌", "うた", 100, 1, 1, false, "ipadic")), matrix)
        val dictionary = CompiledDictionary(ArrayByteSource(packed))
        assertEquals(-100, dictionary.connectionCost(0, 1))
        assertEquals(200, dictionary.connectionCost(1, 0))
    }
    @Test fun mandarinSourceAdmissionIsRegionalAndMergesProvenance() {
        val cp = row("柏林", "bo2 lin2").copy(source = "cedpane")
        val mc = row("樂器", "yue4 qi4").copy(source = "mcbopomofo")
        assertFailsWith<IllegalArgumentException> { compile(ReadingLocale.MANDARIN_TW, listOf(cp)) }
        assertFailsWith<IllegalArgumentException> { compile(ReadingLocale.MANDARIN_CN, listOf(mc)) }
        assertFailsWith<IllegalArgumentException> { compile(ReadingLocale.CANTONESE_HK, listOf(cp)) }
        val packed = compile(ReadingLocale.MANDARIN_CN, listOf(cp, cp.copy(source = "accompanist-common-polyphones")))
        assertEquals(listOf("accompanist-common-polyphones", "cedpane"),
            CompiledDictionary(ArrayByteSource(packed)).lookup("柏林", ParseWorkspace()).single().sourceIds)
        assertEquals("yue4 qi4", CompiledDictionary(ArrayByteSource(compile(ReadingLocale.MANDARIN_TW, listOf(mc))))
            .lookup("樂器", ParseWorkspace()).single().reading)
    }
    @Test fun validationOnlyAndChangedInputsAreRejected() {
        val root = Files.createTempDirectory("phonetics-compiler-test").toFile()
        try {
            val data = root.resolve("data/prepared/test.tsv").also { it.parentFile.mkdirs(); it.writeText("sample") }
            val manifest = root.resolve("manifest.tsv")
            manifest.writeText("test\tdata/prepared/test.tsv\t${sha256(data.readBytes())}\tvalidation-only\n")
            assertFails { verifiedInputs(root, manifest) }
            manifest.writeText("test\tdata/prepared/test.tsv\t${sha256(data.readBytes())}\tredistribute\n")
            assertEquals(data.canonicalFile, verifiedInputs(root, manifest).getValue("test"))
            data.appendText("tampered")
            assertFails { verifiedInputs(root, manifest) }
            manifest.writeText("test\t../../validation-only/test.tsv\tunused\tredistribute\n")
            assertFails { verifiedInputs(root, manifest) }
        } finally { root.deleteRecursively() }
    }
}
