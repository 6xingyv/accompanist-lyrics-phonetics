@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.mocharealm.accompanist.lyrics.phonetics

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.dataWithContentsOfFile

/** Copy pack files AND their notices into the application's resource bundle (see README). */
class IosBundlePackLoader(private val bundle: NSBundle = NSBundle.mainBundle) : PackLoader {
    override fun load(packName: String): ByteSource {
        require(packName.matches(Regex("[a-zA-Z-]+\\.lpd")))
        val path = requireNotNull(bundle.pathForResource(packName.removeSuffix(".lpd"), ofType = "lpd")) { "Missing pack: $packName" }
        val data = requireNotNull(NSData.dataWithContentsOfFile(path, options = NSDataReadingMappedIfSafe, error = null))
        require(data.length in 1uL..Int.MAX_VALUE.toULong())
        return object : ByteSource {
            private val owner = data
            override val size: Int get() = owner.length.toInt()
            override fun byteAt(offset: Int): Int {
                require(offset in 0 until size)
                return owner.bytes!!.reinterpret<UByteVar>()[offset].toInt()
            }
        }
    }
}
