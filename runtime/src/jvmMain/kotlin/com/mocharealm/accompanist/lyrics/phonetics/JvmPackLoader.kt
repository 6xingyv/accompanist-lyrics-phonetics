package com.mocharealm.accompanist.lyrics.phonetics

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private class MappedBytes(buffer: ByteBuffer) : ByteSource {
    private val data = buffer.asReadOnlyBuffer()
    override val size: Int get() = data.limit()
    override fun byteAt(offset: Int): Int = data.get(offset).toInt() and 255
}
/** No heap copy or deserialization for installed pack files. Mapping is owned by the returned source. */
class JvmFilePackLoader(private val directory: Path) : PackLoader {
    override fun load(packName: String): ByteSource {
        require(packName.matches(Regex("[a-zA-Z-]+\\.lpd")))
        return FileChannel.open(directory.resolve(packName), StandardOpenOption.READ).use { file ->
            require(file.size() <= Int.MAX_VALUE)
            MappedBytes(file.map(FileChannel.MapMode.READ_ONLY, 0, file.size()))
        }
    }
}
/** Classpath resources in jars use one owned byte buffer, still no dictionary reconstruction. */
class ClasspathPackLoader(private val classLoader: ClassLoader = ClasspathPackLoader::class.java.classLoader) : PackLoader {
    override fun load(packName: String): ByteSource {
        require(packName.matches(Regex("[a-zA-Z-]+\\.lpd")))
        val bytes = requireNotNull(classLoader.getResourceAsStream(packName)) { "Missing pack: $packName" }.use { it.readBytes() }
        return object : ByteSource {
            override val size: Int get() = bytes.size
            override fun byteAt(offset: Int): Int = bytes[offset].toInt() and 255
        }
    }
}
