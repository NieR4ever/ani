package me.him188.ani.danmaku.server.ktor.plugins

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import me.him188.ani.danmaku.server.util.exception.HttpRequestException

internal fun Application.configureStatuePages() {
    install(StatusPages) {
        exception<Throwable> { call, throwable ->
            when (throwable) {
                is HttpRequestException -> call.respond(HttpStatusCode(throwable.statusCode, throwable.statusMessage))
                else -> {
                    throwable.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, "Internal server error")
                }
            }
        }
    }
}