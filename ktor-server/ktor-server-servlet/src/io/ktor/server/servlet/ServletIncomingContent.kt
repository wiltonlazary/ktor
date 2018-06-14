package io.ktor.server.servlet

import io.ktor.content.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.request.*
import kotlinx.coroutines.experimental.*

abstract class ServletIncomingContent(
    protected val request: ServletApplicationRequest
) : @Suppress("DEPRECATION") IncomingContent {
    override val headers: Headers = request.headers
    override fun multiPartData(): MultiPartData {
        val contentType = request.header(HttpHeaders.ContentType)
                ?: throw IllegalStateException("Content-Type header is required for multipart processing")

        val contentLength = request.header(HttpHeaders.ContentLength)?.toLong()
        val parts = parseMultipart(Unconfined, readChannel(), contentType, contentLength)

        return CIOMultipartData(parts)
    }
}
