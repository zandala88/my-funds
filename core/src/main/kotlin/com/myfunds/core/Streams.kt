package com.myfunds.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

fun decodeUtf8Strict(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { throw IllegalArgumentException("备份不是有效的 UTF-8 文件，原数据未覆盖") }

/** Read a SQLite BLOB through bounded rows, then decode UTF-8 once across chunk boundaries. */
fun readChunked(size: Int, read: (offset: Int, length: Int) -> ByteArray): ByteArray {
    require(size in 0..BackupCodec.MAX_BYTES) { "本机数据超过读取限制" }
    val output = ByteArrayOutputStream(size)
    var offset = 0
    while (offset < size) {
        val length = minOf(256 * 1024, size - offset)
        val bytes = read(offset, length); require(bytes.size == length) { "本机数据读取不完整" }
        output.write(bytes); offset += length
    }
    return output.toByteArray()
}

/** Bounded reads compatible with Android 8; never trusts provider/file metadata. */
fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(output.size().toLong() + count <= limit) { "文件或响应超过大小限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
