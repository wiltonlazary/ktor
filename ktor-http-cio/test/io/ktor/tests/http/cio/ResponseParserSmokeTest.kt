package io.ktor.tests.http.cio

import io.ktor.http.cio.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlin.test.*

class ResponseParserSmokeTest {
    @Test
    fun testParse200OkNoHeaders() = runBlocking {
        val requestText = "HTTP/1.1 200 OK\r\n\r\n"
        val ch = ByteReadChannel(requestText.toByteArray())

        val response = parseResponse(ch)
        try {
            assertNotNull(response)
            assertEquals(200, response!!.status)
            assertEquals("OK", response.statusText.toString())
            assertEquals("HTTP/1.1", response.version.toString())

            assertEquals(0, response.headers.size)
        } finally {
            response?.release()
        }
    }

    @Test
    fun testParse200OkWithHeaders() = runBlocking {
        val requestText = "HTTP/1.1 200 OK\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        val ch = ByteReadChannel(requestText.toByteArray())

        val response = parseResponse(ch)
        try {
            assertNotNull(response)
            assertEquals(200, response!!.status)
            assertEquals("OK", response.statusText.toString())
            assertEquals("HTTP/1.1", response.version.toString())

            assertEquals(2, response.headers.size)
            assertEquals("localhost", response.headers["Host"]?.toString())
            assertEquals("close", response.headers["Connection"]?.toString())
        } finally {
            response?.release()
        }
    }
}