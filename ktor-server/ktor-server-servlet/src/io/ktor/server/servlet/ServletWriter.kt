package io.ktor.server.servlet

import io.ktor.util.cio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.io.*
import kotlinx.io.pool.*
import java.io.*
import java.util.concurrent.TimeoutException
import javax.servlet.*
import kotlin.coroutines.*

internal fun servletWriter(output: ServletOutputStream, parent: CoroutineContext? = null) : ReaderJob {
    val writer = ServletWriter(output)
    return reader(if (parent != null) Unconfined + parent else Unconfined, writer.channel) {
        writer.run()
    }
}

internal val ArrayPool = object : DefaultPool<ByteArray>(1024) {
    override fun produceInstance() = ByteArray(4096)
    override fun validateInstance(instance: ByteArray) {
        if (instance.size != 4096) throw IllegalArgumentException("Tried to recycle wrong ByteArray instance: most likely it hasn't been borrowed from this pool")
    }
}

private const val MAX_COPY_SIZE = 512 * 1024 // 512K

private class ServletWriter(val output: ServletOutputStream) : WriteListener {
    val channel = ByteChannel()

    private val events = Channel<Unit>(2)

    suspend fun run() {
        val buffer = ArrayPool.borrow()
        try {
            output.setWriteListener(this)
            events.receive()
            loop(buffer)

            finish()

            // we shouldn't recycle it in finally
            // because in case of error the buffer could be still hold by servlet container
            // so we simply drop it as buffer leak has only limited performance impact
            // (buffer will be collected by GC and pool will produce another one)
            ArrayPool.recycle(buffer)
        } catch (t: Throwable) {
            onError(t)
        } finally {
            events.close()
        }
    }

    private suspend fun finish() {
        awaitReady()
        output.flush()
        awaitReady()
    }

    private suspend fun loop(buffer: ByteArray) {
        if (channel.availableForRead == 0) {
            awaitReady()
            output.flush()
        }

        var copied = 0L
        while (true) {
            val rc = channel.readAvailable(buffer)
            if (rc == -1) break

            copied += rc
            if (copied > MAX_COPY_SIZE) {
                copied = 0
                yield()
            }

            awaitReady()
            output.write(buffer, 0, rc)
            awaitReady()

            if (channel.availableForRead == 0) output.flush()
        }
    }

    private suspend fun awaitReady() {
        if (output.isReady) return
        return awaitReadySuspend()
    }

    private suspend fun awaitReadySuspend() {
        do {
            events.receive()
        } while (!output.isReady)
    }

    override fun onWritePossible() {
        try {
            if (!events.offer(Unit)) {
                launch(Unconfined) {
                    events.send(Unit)
                }
            }
        } catch (ignore: Throwable) {
        }
    }

    override fun onError(t: Throwable) {
        val wrapped = wrapException(t)
        events.cancel(wrapped)
        channel.cancel(wrapped)
    }

    private fun wrapException(cause: Throwable): Throwable {
        return if (cause is IOException || cause is TimeoutException) {
            ChannelWriteException("Failed to write to servlet async stream", exception = cause)
        } else cause
    }
}