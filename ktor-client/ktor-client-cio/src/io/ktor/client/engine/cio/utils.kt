package io.ktor.client.engine.cio

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.content.*
import io.ktor.http.*
import io.ktor.http.cio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*

internal suspend fun DefaultHttpRequest.write(output: ByteWriteChannel) {
    val builder = RequestResponseBuilder()

    val contentLength = headers[HttpHeaders.ContentLength] ?: content.contentLength?.toString()
    val contentEncoding = headers[HttpHeaders.TransferEncoding]
    val responseEncoding = content.headers[HttpHeaders.TransferEncoding]
    val chunked = contentLength == null || responseEncoding == "chunked" || contentEncoding == "chunked"

    try {
        builder.requestLine(method, url.fullPath, HttpProtocolVersion.HTTP_1_1.toString())
        builder.headerLine("Host", url.hostWithPort)

        if (!headers.contains(HttpHeaders.UserAgent)) {
            builder.headerLine("User-Agent", "CIO/ktor")
        }

        mergeHeaders(headers, content) { key, value ->
            builder.headerLine(key, value)
        }

        if (chunked && contentEncoding == null && responseEncoding == null && content !is OutgoingContent.NoContent) {
            builder.headerLine(HttpHeaders.TransferEncoding, "chunked")
        }

        builder.emptyLine()
        output.writePacket(builder.build())
        output.flush()
    } finally {
        builder.release()
    }

    val content = content
    if (content is OutgoingContent.NoContent)
        return

    val chunkedJob: EncoderJob? = if (chunked) encodeChunked(output, Unconfined) else null
    val channel = chunkedJob?.channel ?: output

    try {
        when (content) {
            is OutgoingContent.NoContent -> return
            is OutgoingContent.ByteArrayContent -> channel.writeFully(content.bytes())
            is OutgoingContent.ReadChannelContent -> content.readFrom().copyAndClose(channel)
            is OutgoingContent.WriteChannelContent -> content.writeTo(channel)
            is OutgoingContent.ProtocolUpgrade -> UnsupportedContentTypeException(content)
        }
    } catch (cause: Throwable) {
        channel.close(cause)
        executionContext.completeExceptionally(cause)
    } finally {
        channel.flush()
        chunkedJob?.channel?.close()
        chunkedJob?.join()
        executionContext.complete(Unit)
    }
}
