package com.example.support

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Test images, drawn and encoded when they are asked for.
 *
 * Generated rather than committed so the repository never carries a binary whose contents nobody
 * can read in a diff, and so a test that needs an odd shape or a particular colour can say so
 * rather than reaching for another checked-in file.
 */
internal fun pngBytes(
    width: Int = 800,
    height: Int = 600,
    colour: Color = Color.RED,
): ByteArray = encode("png", solid(width, height, colour))

internal fun jpegBytes(
    width: Int = 800,
    height: Int = 600,
    colour: Color = Color.RED,
): ByteArray = encode("jpeg", solid(width, height, colour))

internal fun gifBytes(): ByteArray = encode("gif", solid(10, 10, Color.BLUE))

/** Fully transparent, so a decoder that ignores alpha produces a black square instead of a white one. */
internal fun transparentPngBytes(): ByteArray = encode("png", BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))

/**
 * A portrait whose top and bottom thirds differ from its middle, so a test can tell a centre crop
 * from a squash or a corner crop by reading one pixel.
 */
internal fun bandedPortraitPngBytes(): ByteArray {
    val image = BufferedImage(300, 900, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.color = Color.RED
    canvas.fillRect(0, 0, 300, 900)
    canvas.color = Color.GREEN
    canvas.fillRect(0, 300, 300, 300)
    canvas.dispose()
    return encode("png", image)
}

/** Bytes that open like a PNG and stop, so the header parses and the pixel data cannot. */
internal fun truncatedPngBytes(): ByteArray = pngBytes().copyOf(40)

private fun solid(
    width: Int,
    height: Int,
    colour: Color,
): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val canvas = image.createGraphics()
    canvas.color = colour
    canvas.fillRect(0, 0, width, height)
    canvas.dispose()
    return image
}

private fun encode(
    format: String,
    image: BufferedImage,
): ByteArray =
    ByteArrayOutputStream()
        .also { check(ImageIO.write(image, format, it)) { "the JVM has no $format writer" } }
        .toByteArray()
