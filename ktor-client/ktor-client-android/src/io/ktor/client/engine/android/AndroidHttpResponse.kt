package io.ktor.client.engine.android

import io.ktor.client.call.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import kotlinx.coroutines.io.*
import java.net.*

class AndroidHttpResponse(
    override val call: HttpClientCall,
    override val content: ByteReadChannel,
    override val executionContext: Job,
    override val headers: Headers,
    override val requestTime: GMTDate,
    override val responseTime: GMTDate,
    override val status: HttpStatusCode,
    override val version: HttpProtocolVersion,
    private val connection: HttpURLConnection
) : HttpResponse {

    override fun close() {
        connection.disconnect()
    }
}