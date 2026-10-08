package com.mocharealm.accompanist.lyrics.phonetics

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** APK assets must be stored uncompressed (.lpd). Each mapping is immutable and shareable. */
class AndroidPackLoader(context: Context) : PackLoader {
    private val assets = context.applicationContext.assets
    override fun load(packName: String): ByteSource {
        require(packName.matches(Regex("[a-zA-Z-]+\\.lpd")))
        val mapping = assets.openFd(packName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                require(descriptor.length in 1..Int.MAX_VALUE.toLong())
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length).asReadOnlyBuffer()
            }
        }
        return object : ByteSource {
            private val data: ByteBuffer = mapping
            override val size: Int get() = data.limit()
            override fun byteAt(offset: Int): Int = data.get(offset).toInt() and 255
        }
    }
}
