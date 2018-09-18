package io.ktor.client.engine.jetty

import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.io.*
import kotlinx.coroutines.future.*
import org.eclipse.jetty.http.*
import org.eclipse.jetty.http2.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.http2.frames.*
import org.eclipse.jetty.util.*
import java.io.*
import java.nio.*
import java.nio.channels.*
import java.util.concurrent.*

internal data class StatusWithHeaders(val statusCode: HttpStatusCode, val headers: Headers)

private data class JettyResponseChunk(val buffer: ByteBuffer, val callback: Callback)

internal class JettyResponseListener(
    private val session: HTTP2ClientSession,
    private val channel: ByteWriteChannel,
    private val dispatcher: CoroutineDispatcher,
    private val context: CompletableDeferred<Unit>
) : Stream.Listener {
    private val headersBuilder: HeadersBuilder = HeadersBuilder()
    private val onHeadersReceived: CompletableFuture<HttpStatusCode?> = CompletableFuture()
    private val backendChannel = Channel<JettyResponseChunk>(Channel.UNLIMITED)

    init {
        runResponseProcessing()
    }

    override fun onPush(stream: Stream, frame: PushPromiseFrame): Stream.Listener {
        stream.reset(ResetFrame(frame.promisedStreamId, ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP)
        return Ignore
    }

    override fun onReset(stream: Stream, frame: ResetFrame) {
        val error = when (frame.error) {
            0 -> null
            ErrorCode.CANCEL_STREAM_ERROR.code -> ClosedChannelException()
            else -> {
                val code = ErrorCode.from(frame.error)
                IOException("Connection reset ${code?.name ?: "with unknown error code ${frame.error}"}")
            }
        }

        error?.let { backendChannel.close(it) }
        onHeadersReceived.complete(null)
    }

    override fun onData(stream: Stream, frame: DataFrame, callback: Callback) {
        val data = frame.data.copy()
        try {
            if (!backendChannel.offer(JettyResponseChunk(data, callback))) {
                throw IOException("backendChannel.offer() failed")
            }

            if (frame.isEndStream) backendChannel.close()
        } catch (cause: Throwable) {
            backendChannel.close(cause)
            callback.succeeded()
        }
    }

    override fun onHeaders(stream: Stream, frame: HeadersFrame) {
        frame.metaData.fields.forEach { field ->
            headersBuilder.append(field.name, field.value)
        }

        if (frame.isEndStream) backendChannel.close()

        onHeadersReceived.complete((frame.metaData as? MetaData.Response)?.let {
            val (status, reason) = it.status to it.reason
            reason?.let { HttpStatusCode(status, it) } ?: HttpStatusCode.fromValue(status)
        })
    }

    suspend fun awaitHeaders(): StatusWithHeaders {
        onHeadersReceived.await()
        val statusCode = onHeadersReceived.get() ?: throw IOException("Connection reset")
        return StatusWithHeaders(statusCode, headersBuilder.build())
    }

    private fun runResponseProcessing() = launch(dispatcher) {
        try {
            while (!backendChannel.isClosedForReceive) {
                val (buffer, callback) = backendChannel.receiveOrNull() ?: break
                try {
                    if (buffer.remaining() > 0) channel.writeFully(buffer)
                    callback.succeeded()
                } catch (cause: ClosedWriteChannelException) {
                    callback.failed(cause)
                    session.endPoint.close()
                    break
                } catch (cause: Throwable) {
                    callback.failed(cause)
                    session.endPoint.close()
                    throw cause
                }
            }
        } catch (cause: Throwable) {
            channel.close(cause)
            this@JettyResponseListener.context.completeExceptionally(cause)
        } finally {
            backendChannel.close()
            backendChannel.consumeEach { it.callback.succeeded() }

            channel.close()
            this@JettyResponseListener.context.complete(Unit)
        }
    }

    companion object {
        private val Ignore = Stream.Listener.Adapter()
    }
}
