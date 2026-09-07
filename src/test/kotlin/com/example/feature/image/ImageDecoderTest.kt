package com.example.feature.image

import com.example.api.common.ErrorCode
import com.example.api.common.FieldLimits
import com.example.common.BusinessRuleException
import com.example.support.bandedPortraitPngBytes
import com.example.support.gifBytes
import com.example.support.jpegBytes
import com.example.support.pngBytes
import com.example.support.transparentPngBytes
import com.example.support.truncatedPngBytes
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The decoder is where an upload stops being whatever the client sent, so these are the tests that
 * matter most in the feature. No database and no server: the rules are all in one pure function.
 */
class ImageDecoderTest {
    @Test
    fun `normalises a landscape png to a square jpeg`() {
        val result = runBlocking { ImageDecoder.toAvatarJpeg(pngBytes(width = 900, height = 400)) }

        val decoded = result.decoded()
        assertEquals(FieldLimits.AVATAR_EDGE_PX, decoded.width)
        assertEquals(FieldLimits.AVATAR_EDGE_PX, decoded.height)
        assertEquals("image/jpeg", result.contentType)
        assertTrue(result.bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())), "expected JPEG bytes")
    }

    @Test
    fun `re-encodes a jpeg as well as a png`() {
        val result = runBlocking { ImageDecoder.toAvatarJpeg(jpegBytes()) }

        assertEquals(FieldLimits.AVATAR_EDGE_PX, result.decoded().width)
    }

    @Test
    fun `crops a portrait from the centre rather than squashing it`() {
        // The source is 300x900 and green only across its middle third, which is exactly the square
        // a centre crop takes. A squash would bring the red in with it, and a top crop would be all red.
        val result = runBlocking { ImageDecoder.toAvatarJpeg(bandedPortraitPngBytes()) }

        val middle = result.decoded().getRGB(FieldLimits.AVATAR_EDGE_PX / 2, FieldLimits.AVATAR_EDGE_PX / 2)
        assertTrue(middle.green() > 200, "expected the middle band, got ${middle.describe()}")
        assertTrue(middle.red() < 100, "expected the middle band, got ${middle.describe()}")
    }

    @Test
    fun `flattens transparency onto white rather than black`() {
        val result = runBlocking { ImageDecoder.toAvatarJpeg(transparentPngBytes()) }

        val pixel = result.decoded().getRGB(FieldLimits.AVATAR_EDGE_PX / 2, FieldLimits.AVATAR_EDGE_PX / 2)
        assertTrue(
            pixel.red() > 240 && pixel.green() > 240 && pixel.blue() > 240,
            "a transparent upload must not arrive as a black square, got ${pixel.describe()}",
        )
    }

    @Test
    fun `drops exif rather than copying it forward`() {
        val withExif = jpegWithExifSegment()
        assertTrue(withExif.containsBytes(APP1_MARKER), "the fixture is meant to carry an APP1 segment")
        assertTrue(withExif.containsBytes(EXIF_IDENTIFIER), "the fixture is meant to name that segment as EXIF")

        val result = runBlocking { ImageDecoder.toAvatarJpeg(withExif) }

        assertFalse(
            result.bytes.containsBytes(APP1_MARKER),
            "re-encoding must leave EXIF behind; a camera roll upload carries GPS coordinates in it",
        )
        assertFalse(result.bytes.containsBytes(EXIF_IDENTIFIER), "no EXIF identifier may survive re-encoding")
    }

    @Test
    fun `refuses a gif`() {
        val failure = assertFailsWith<BusinessRuleException> { runBlocking { ImageDecoder.toAvatarJpeg(gifBytes()) } }

        assertEquals(ErrorCode.UNSUPPORTED_IMAGE, failure.code)
    }

    @Test
    fun `refuses an svg however it is labelled`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>""".toByteArray()

        val failure = assertFailsWith<BusinessRuleException> { runBlocking { ImageDecoder.toAvatarJpeg(svg) } }

        assertEquals(ErrorCode.UNSUPPORTED_IMAGE, failure.code)
    }

    @Test
    fun `refuses a png whose pixels are truncated`() {
        val failure =
            assertFailsWith<BusinessRuleException> { runBlocking { ImageDecoder.toAvatarJpeg(truncatedPngBytes()) } }

        assertEquals(ErrorCode.UNSUPPORTED_IMAGE, failure.code)
    }

    @Test
    fun `refuses a header claiming more pixels than the cap allows`() {
        // A decompression bomb: a few hundred bytes on the wire that would be gigabytes decoded.
        // Rejecting it has to happen on the header, which is why this fixture never decodes at all.
        val bomb = pngBytes(width = 8, height = 8).withPngDimensions(20_000, 20_000)

        val failure = assertFailsWith<BusinessRuleException> { runBlocking { ImageDecoder.toAvatarJpeg(bomb) } }

        assertEquals(ErrorCode.UNSUPPORTED_IMAGE, failure.code)
    }

    @Test
    fun `the digest describes the output and not the upload`() {
        val source = pngBytes()

        val first = runBlocking { ImageDecoder.toAvatarJpeg(source) }
        val second = runBlocking { ImageDecoder.toAvatarJpeg(source) }

        assertEquals(first.sha256, second.sha256, "the same upload must produce the same address twice")
        assertEquals(first.bytes.sha256Hex(), first.sha256, "the digest must describe the bytes that are served")
        assertNotEquals(source.sha256Hex(), first.sha256, "the upload is not what gets stored")
    }
}

private const val CHANNEL_MASK = 0xFF

/** The APP1 marker. A two-byte length follows it, so the identifier below is not adjacent to it. */
private val APP1_MARKER = byteArrayOf(0xFF.toByte(), 0xE1.toByte())

/** What names an APP1 segment as EXIF rather than as one of the other things APP1 can carry. */
private val EXIF_IDENTIFIER = "Exif".toByteArray(Charsets.US_ASCII)

private fun NewImage.decoded(): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes)) ?: error("the decoder produced something that is not an image")

private fun Int.red(): Int = (this shr 16) and CHANNEL_MASK

private fun Int.green(): Int = (this shr 8) and CHANNEL_MASK

private fun Int.blue(): Int = this and CHANNEL_MASK

private fun Int.describe(): String = "rgb(${red()}, ${green()}, ${blue()})"

/** A real JPEG with an APP1 segment spliced in just after the start-of-image marker. */
private fun jpegWithExifSegment(): ByteArray {
    val base = jpegBytes(width = 64, height = 64)
    val payload = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + ByteArray(64) { 0x2A }
    val length = payload.size + 2
    val segment =
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    return base.copyOfRange(0, 2) + segment + base.copyOfRange(2, base.size)
}

/**
 * Rewrites the width and height in a PNG's IHDR chunk, fixing the chunk's checksum so the reader
 * accepts the header and reports the dimensions it claims rather than refusing it as corrupt.
 */
private fun ByteArray.withPngDimensions(
    width: Int,
    height: Int,
): ByteArray {
    val patched = copyOf()
    width.writeBigEndianInto(patched, at = 16)
    height.writeBigEndianInto(patched, at = 20)

    // The checksum covers the chunk's type and data: "IHDR" plus its 13 bytes, so offsets 12 to 28.
    val checksum = CRC32().apply { update(patched, 12, 17) }.value
    checksum.toInt().writeBigEndianInto(patched, at = 29)
    return patched
}

private fun Int.writeBigEndianInto(
    target: ByteArray,
    at: Int,
) {
    target[at] = (this shr 24).toByte()
    target[at + 1] = (this shr 16).toByte()
    target[at + 2] = (this shr 8).toByte()
    target[at + 3] = this.toByte()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

private fun ByteArray.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
