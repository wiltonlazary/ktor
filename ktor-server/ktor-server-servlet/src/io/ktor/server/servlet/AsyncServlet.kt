package io.ktor.server.servlet

import io.ktor.application.*
import io.ktor.util.cio.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.server.engine.*
import kotlinx.coroutines.io.*
import java.io.*
import java.lang.reflect.*
import javax.servlet.http.*
import kotlin.coroutines.*

open class AsyncServletApplicationCall(
    application: Application,
    servletRequest: HttpServletRequest,
    servletResponse: HttpServletResponse,
    engineContext: CoroutineContext,
    userContext: CoroutineContext,
    upgrade: ServletUpgrade
) : BaseApplicationCall(application) {

    override val request: ServletApplicationRequest = AsyncServletApplicationRequest(this, servletRequest)

    override val response: ServletApplicationResponse = AsyncServletApplicationResponse(
        this, servletRequest, servletResponse, engineContext, userContext, upgrade
    )
}

class AsyncServletApplicationRequest(
    call: ApplicationCall, servletRequest: HttpServletRequest
) : ServletApplicationRequest(call, servletRequest) {

    private val copyJob by lazy { servletReader(servletRequest.inputStream) }

    override fun receiveChannel(): ByteReadChannel = copyJob.channel
}

open class AsyncServletApplicationResponse(
    call: ApplicationCall,
    protected val servletRequest: HttpServletRequest,
    servletResponse: HttpServletResponse,
    private val engineContext: CoroutineContext,
    private val userContext: CoroutineContext,
    private val servletUpgradeImpl: ServletUpgrade
) : ServletApplicationResponse(call, servletResponse) {
    override fun createResponseJob(): ReaderJob =
        servletWriter(servletResponse.outputStream)

    final override suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade) {
        try {
            servletResponse.flushBuffer()
        } catch (e: IOException) {
            throw ChannelWriteException("Cannot write HTTP upgrade response", e)
        }

        completed = true

        servletUpgradeImpl.performUpgrade(upgrade, servletRequest, servletResponse, engineContext, userContext)
    }

    override fun push(builder: ResponsePushBuilder) {
        if (!tryPush(servletRequest, builder)) {
            super.push(builder)
        }
    }

    private fun tryPush(request: HttpServletRequest, builder: ResponsePushBuilder): Boolean {
        return foundPushImpls.any { function ->
            tryInvoke(function, request, builder)
        }
    }

    companion object {
        private val foundPushImpls by lazy {
            listOf("io.ktor.servlet.v4.PushKt.doPush").mapNotNull { tryFind(it) }
        }

        private fun tryFind(spec: String): Method? = try {
            require("." in spec)
            val methodName = spec.substringAfterLast(".")

            Class.forName(spec.substringBeforeLast(".")).methods.singleOrNull { it.name == methodName }
        } catch (ignore: ReflectiveOperationException) {
            null
        } catch (ignore: LinkageError) {
            null
        }

        private fun tryInvoke(function: Method, request: HttpServletRequest, builder: ResponsePushBuilder) = try {
            function.invoke(null, request, builder) as Boolean
        } catch (ignore: ReflectiveOperationException) {
            false
        } catch (ignore: LinkageError) {
            false
        }
    }
}
