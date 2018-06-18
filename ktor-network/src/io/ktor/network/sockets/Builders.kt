package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.network.util.*
import kotlinx.coroutines.experimental.*
import java.net.*
import java.nio.channels.*

class SocketOptions private constructor(private val allOptions: MutableMap<SocketOption<*>, Any?> = HashMap()) {
    internal constructor() : this(HashMap())

    fun copy() = SocketOptions(allOptions.toMutableMap())

    operator fun <T> get(option: SocketOption<T>): T = @Suppress("UNCHECKED_CAST") (allOptions[option] as T)

    operator fun <T> set(option: SocketOption<T>, value: T) {
        allOptions[option] = value
    }

    fun list(): List<Pair<SocketOption<*>, Any?>> = allOptions.entries.map { Pair(it.key, it.value) }

    companion object {
        val Empty = SocketOptions()
    }
}

interface Configurable<out T : Configurable<T>> {
    var options: SocketOptions
    var coroutineDispatcher: CoroutineDispatcher

    fun configure(block: SocketOptions.() -> Unit): T {
        val newOptions = options.copy()
        block(newOptions)
        options = newOptions

        @Suppress("UNCHECKED_CAST")
        return this as T
    }
}

fun <T: Configurable<T>> T.tcpNoDelay(): T {
    return configure {
        this[StandardSocketOptions.TCP_NODELAY] = true
    }
}

@Deprecated("Specify selector manager explicitly", level = DeprecationLevel.ERROR)
fun aSocket(): SocketBuilder = TODO()

fun aSocket(selector: SelectorManager, coroutineDispatcher: CoroutineDispatcher = ioCoroutineDispatcher)
        = SocketBuilder(selector, SocketOptions.Empty, coroutineDispatcher)

class SocketBuilder internal constructor(val selector: SelectorManager,
                                         override var options: SocketOptions,
                                         override var coroutineDispatcher: CoroutineDispatcher) : Configurable<SocketBuilder> {
    fun tcp() = TcpSocketBuilder(selector, options, coroutineDispatcher)
    fun udp() = UDPSocketBuilder(selector, options, coroutineDispatcher)
}

class TcpSocketBuilder internal constructor(val selector: SelectorManager,
                                            override var options: SocketOptions,
                                            override var coroutineDispatcher: CoroutineDispatcher) : Configurable<TcpSocketBuilder> {
    suspend fun connect(hostname: String, port: Int) = connect(InetSocketAddress(hostname, port))
    fun bind(hostname: String = "0.0.0.0", port: Int = 0) = bind(InetSocketAddress(hostname, port))

    suspend fun connect(remoteAddress: SocketAddress): Socket {
        return selector.buildOrClose({ openSocketChannel() }) {
            assignOptions(options)
            nonBlocking()

            SocketImpl(this, selector, coroutineDispatcher).apply {
                connect(remoteAddress)
            }
        }
    }

    fun bind(localAddress: SocketAddress? = null): ServerSocket {
        return selector.buildOrClose({ openServerSocketChannel() }) {
            assignOptions(options)
            nonBlocking()

            ServerSocketImpl(this, selector, coroutineDispatcher).apply {
                channel.bind(localAddress)
            }
        }
    }
}

class UDPSocketBuilder internal constructor(val selector: SelectorManager,
                                            override var options: SocketOptions,
                                            override var coroutineDispatcher: CoroutineDispatcher) : Configurable<UDPSocketBuilder> {
    fun bind(localAddress: SocketAddress? = null): BoundDatagramSocket {
        return selector.buildOrClose({ openDatagramChannel() }) {
            assignOptions(options)
            nonBlocking()

            DatagramSocketImpl(this, selector, coroutineDispatcher).apply {
                channel.bind(localAddress)
            }
        }
    }

    fun connect(remoteAddress: SocketAddress, localAddress: SocketAddress? = null): ConnectedDatagramSocket {
        return selector.buildOrClose({ openDatagramChannel() }) {
            assignOptions(options)
            nonBlocking()

            DatagramSocketImpl(this, selector, coroutineDispatcher).apply {
                channel.bind(localAddress)
                channel.connect(remoteAddress)
            }
        }
    }
}

private fun NetworkChannel.assignOptions(options: SocketOptions) {
    options.list().forEach { (k, v) ->
        @Suppress("UNCHECKED_CAST")
        (setOption(k as SocketOption<Any?>, v))
    }
}

private fun SelectableChannel.nonBlocking() {
    configureBlocking(false)
}