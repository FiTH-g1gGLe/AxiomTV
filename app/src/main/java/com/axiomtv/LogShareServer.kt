package com.axiomtv

import timber.log.Timber
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Serves the debug log to a phone on the same Wi-Fi, so it can be copied from a TV box
 * (which has no easy way to paste). A random path stops anyone else on the network guessing it.
 */
class LogShareServer {
    @Volatile
    private var socket: ServerSocket? = null
    private val token = Random.nextLong(100_000_000L, 999_999_999L).toString(36)

    fun start(): String? {
        val ip = PhoneLoginServer.localIp() ?: return null
        val server = listOf(8091, 8092, 0).firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null
        socket = server
        thread(name = "log-share", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        client.use { sock ->
                            sock.soTimeout = 10_000
                            val request = sock.getInputStream().bufferedReader().readLine().orEmpty()
                            val ok = request.split(" ").getOrNull(1) == "/$token"
                            val body = if (ok) page(AxiomLog.dump()) else "Not found"
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val head =
                                "HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                            sock.getOutputStream().apply {
                                write(head.toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }.onFailure { Timber.w(it, "Log share request failed") }
                }
            }
        }
        return "http://$ip:${server.localPort}/$token"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun page(log: String): String {
        val escaped = log.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AxiomTV debug log</title>
            <style>body{margin:0;background:#0F1012;color:#F2F3F5;font-family:system-ui,sans-serif}
            header{position:sticky;top:0;background:#16181C;padding:14px 16px;display:flex;gap:12px;align-items:center}
            h1{font-size:17px;margin:0;flex:1}button{background:#E5A00D;color:#1A1300;border:0;border-radius:22px;padding:12px 20px;font-size:16px;font-weight:700}
            pre{white-space:pre-wrap;word-break:break-word;font-size:12px;padding:16px;margin:0}</style></head><body>
            <header><h1>AxiomTV debug log</h1><button id="b">Copy</button></header>
            <pre id="log">$escaped</pre>
            <script>document.getElementById('b').onclick=function(){var t=document.getElementById('log').innerText;
            function done(){document.getElementById('b').textContent='Copied ✓'}
            if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done)}else{
            var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove();done()}}</script>
            </body></html>
        """.trimIndent()
    }
}
