package io.ktor.network.sockets

import io.ktor.network.selector.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.ByteChannel
import kotlinx.io.pool.*
import java.nio.channels.*

internal suspend fun attachForWritingImpl(
        channel: ByteChannel,
        nioChannel: WritableByteChannel,
        selectable: Selectable,
        selector: SelectorManager,
        pool: ObjectPool<ByteBuffer>
) {
    val buffer = pool.borrow()

    try {
        while (true) {
            buffer.clear()
            if (channel.readAvailable(buffer) == -1) {
                break
            }
            buffer.flip()

            while (buffer.hasRemaining()) {
                val rc = nioChannel.write(buffer)
                if (rc == 0) {
                    selectable.interestOp(SelectInterest.WRITE, true)
                    selector.select(selectable, SelectInterest.WRITE)
                } else {
                    selectable.interestOp(SelectInterest.WRITE, false)
                }
            }
        }
    } finally {
        pool.recycle(buffer)
        if (nioChannel is SocketChannel) {
            try {
                nioChannel.shutdownOutput()
            } catch (ignore: ClosedChannelException) {
            }
        }
    }
}

internal suspend fun attachForWritingDirectImpl(
        channel: ByteChannel,
        nioChannel: WritableByteChannel,
        selectable: Selectable,
        selector: SelectorManager
) {

    selectable.interestOp(SelectInterest.WRITE, false)
    try {
        channel.lookAheadSuspend {
            while (true) {
                val buffer = request(0, 1)
                if (buffer == null) {
//                        if (channel.isClosedForRead) break
                    if (!awaitAtLeast(1)) break
                    continue
                }

                while (buffer.hasRemaining()) {
                    val r = nioChannel.write(buffer)

                    if (r == 0) {
                        selectable.interestOp(SelectInterest.WRITE, true)
                        selector.select(selectable, SelectInterest.WRITE)
                    } else {
                        consumed(r)
                    }
                }
            }
        }
    } finally {
        selectable.interestOp(SelectInterest.WRITE, false)
        if (nioChannel is SocketChannel) {
            try {
                nioChannel.shutdownOutput()
            } catch (ignore: ClosedChannelException) {
            }
        }
    }
}