package com.emprendedorlatam.embymirror

import fi.iki.elonen.NanoHTTPD

class StreamServer(
    port: Int,
    private val publisher: StreamPublisher
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            "/channels.m3u" -> {
                val ip = NetUtils.firstIpv4Address()
                val content = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXTINF:-1 tvg-id=\"androidmirror\" tvg-name=\"Android Mirror\" group-title=\"Mirror\",Android Mirror")
                    appendLine("http://$ip:${listeningPort}/stream.ts")
                }
                newFixedLengthResponse(Response.Status.OK, "audio/x-mpegurl", content)
            }
            "/stream.ts" -> {
                val client = publisher.registerClient()
                val response = newChunkedResponse(Response.Status.OK, "video/mp2t", client)
                response.addHeader("Cache-Control", "no-store")
                response.addHeader("Connection", "close")
                response
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }
}
