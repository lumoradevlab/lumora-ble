package dev.lumora.ble.core

/**
 * Little-endian accessors — every protocol here encodes multi-byte ints LE.
 *
 * In `core` rather than `transport` because they are pure Kotlin with no
 * Android dependency, and the protocol parsers that use them are too. Keeping
 * them out of the Android layer is what lets those parsers stay JVM-testable.
 */
fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u32le(i: Int): Long =
    (u16le(i).toLong()) or (u16le(i + 2).toLong() shl 16)
fun ByteArray.i16le(i: Int): Int = u16le(i).toShort().toInt()

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
