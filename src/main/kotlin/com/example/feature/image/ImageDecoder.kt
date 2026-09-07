package com.example.feature.image

import com.example.api.common.ErrorCode
import com.example.api.common.FieldLimits
import com.example.common.BusinessRuleException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.ImageInputStream

private const val JPEG_CONTENT_TYPE = "image/jpeg"
private const val JPEG_FORMAT = "jpeg"
private const val JPEG_QUALITY = 0.85f

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * Turns whatever a client uploaded into the one shape this server stores: a square JPEG of
 * [FieldLimits.AVATAR_EDGE_PX] a side.
 *
 * This is the security boundary of the upload feature, and normalising is what makes it one. The
 * output is written pixel by pixel by an encoder we drive, so nothing the client hid in the file
 * survives: no EXIF, and with it none of the GPS coordinates a phone writes into a camera roll;
 * no trailing archive or script riding behind a valid image header; no metadata block aimed at
 * whatever eventually renders it.
 *
 * Kept free of Ktor and Exposed so the rules can be tested without a server or a database.
 */
object ImageDecoder {
    init {
        // JVM-wide static, so it is set once here rather than per upload: writing it on each call
        // would race on the field, and it has to hold before any reader asks for a scratch file on
        // a container filesystem that is ephemeral and may be read only.
        ImageIO.setUseCache(false)
    }

    suspend fun toAvatarJpeg(source: ByteArray): NewImage = withContext(Dispatchers.Default) { normalise(source) }

    private fun normalise(source: ByteArray): NewImage {
        requireSupportedMagic(source)

        val square = readSquare(source)
        val jpeg = encodeJpeg(square)

        return NewImage(
            contentType = JPEG_CONTENT_TYPE,
            bytes = jpeg,
            width = square.width,
            height = square.height,
            // Of the output, so the ETag describes the bytes that are actually served. Two uploads
            // of the same picture in different formats land on the same digest, which is correct:
            // they produce the same avatar.
            sha256 = jpeg.sha256Hex(),
        )
    }

    /**
     * The file's own leading bytes decide what it is. The declared `Content-Type` and the filename
     * are written by the caller and are never consulted, so renaming a script to `.png` changes
     * nothing here.
     *
     * The list is JPEG and PNG and should stay that way. SVG in particular is XML, carries script,
     * and is rendered as a document rather than decoded as pixels — adding it would hand anything
     * that displays an avatar a way to run code.
     */
    private fun requireSupportedMagic(source: ByteArray) {
        if (!source.startsWith(JPEG_MAGIC) && !source.startsWith(PNG_MAGIC)) {
            reject("An avatar must be a JPEG or a PNG.")
        }
    }

    /**
     * Reads the upload down to a centred square.
     *
     * The dimensions are taken from the header before a single pixel is decoded, because that is
     * the only moment a decompression bomb is cheap to refuse: a 20000 x 20000 PNG is well under a
     * megabyte on the wire and 1.6 GB once decoded. Past the header the reader is asked to
     * subsample, so the buffer it fills stays a few megabytes whatever the source claims.
     */
    private fun readSquare(source: ByteArray): BufferedImage {
        val stream: ImageInputStream =
            ImageIO.createImageInputStream(ByteArrayInputStream(source))
                ?: reject("That image could not be read.")

        return stream.use {
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: reject("That image could not be read.")
            try {
                // seekForwardOnly, and ignoreMetadata so a file carrying thousands of ancillary
                // chunks costs nothing to open. Dropping metadata here is also the first of the
                // two reasons no EXIF can reach the output.
                reader.setInput(stream, true, true)
                cropToSquare(reader, readDimensions(reader))
            } finally {
                reader.dispose()
            }
        }
    }

    private fun readDimensions(reader: ImageReader): Pair<Int, Int> {
        val size =
            runCatching { reader.getWidth(0) to reader.getHeight(0) }
                .getOrElse { reject("That image could not be read.") }
        val (width, height) = size

        if (width <= 0 || height <= 0) {
            reject("That image could not be read.")
        }
        if (width > FieldLimits.MAX_AVATAR_SOURCE_EDGE_PX || height > FieldLimits.MAX_AVATAR_SOURCE_EDGE_PX) {
            reject("An avatar must be at most ${FieldLimits.MAX_AVATAR_SOURCE_EDGE_PX} pixels on a side.")
        }
        return size
    }

    private fun cropToSquare(
        reader: ImageReader,
        size: Pair<Int, Int>,
    ): BufferedImage {
        val (width, height) = size
        val edge = FieldLimits.AVATAR_EDGE_PX

        // Decode at roughly twice the edge we need, so the downscale below still has detail to work
        // with while the decoded buffer stays small no matter how large the source is.
        val step = maxOf(1, minOf(width, height) / (2 * edge))
        val param =
            reader.defaultReadParam.apply {
                setSourceSubsampling(step, step, 0, 0)
            }
        val decoded =
            runCatching { reader.read(0, param) }
                .getOrElse { reject("That image could not be read.") }

        val side = minOf(decoded.width, decoded.height)
        val left = (decoded.width - side) / 2
        val top = (decoded.height - side) / 2

        val target = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
        val canvas = target.createGraphics()
        try {
            // TYPE_INT_RGB has no alpha channel, so anything transparent in the source composites
            // against whatever is already in the buffer — black, by default. Painting white first
            // is what stops a PNG with a transparent background arriving as a black square.
            canvas.color = Color.WHITE
            canvas.fillRect(0, 0, edge, edge)
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            // Centre crop and scale in one step; a portrait loses its top and bottom rather than
            // being squashed into the square.
            canvas.drawImage(decoded, 0, 0, edge, edge, left, top, left + side, top + side, null)
        } finally {
            canvas.dispose()
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName(JPEG_FORMAT).asSequence().firstOrNull()
                ?: error("The JVM has no JPEG writer, which it is required to ship.")
        val sink = ByteArrayOutputStream()

        try {
            ImageIO.createImageOutputStream(sink).use { output ->
                writer.output = output
                val param =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                // The null metadata argument is the second reason nothing from the source survives:
                // there is no stream metadata to copy forward, so the output carries none.
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return sink.toByteArray()
    }

    private fun reject(message: String): Nothing = throw BusinessRuleException(ErrorCode.UNSUPPORTED_IMAGE, message)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
