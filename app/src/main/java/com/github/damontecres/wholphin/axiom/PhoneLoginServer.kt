package com.github.damontecres.wholphin.axiom

import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Tiny web page served by the TV box itself, so you can type your login on your phone.
 * Details go straight from the phone to the box over your home Wi-Fi: no accounts, no cloud.
 */
class PhoneLoginServer(
    private val logo: ByteArray? = null,
    private val onDetails: (server: String, username: String, password: String) -> Unit,
) {
    @Volatile
    var code: String = newCode()
        private set

    @Volatile
    private var socket: ServerSocket? = null
    private var wrongAttempts = 0

    /** e.g. "http://192.168.1.50:8088", or null if the box isn't on a network */
    var address: String? = null
        private set

    fun start(): String? {
        if (socket != null) return address
        val ip = localIp() ?: return null
        val server =
            PORTS.firstNotNullOfOrNull { port -> runCatching { ServerSocket(port) }.getOrNull() } ?: return null
        socket = server
        address = "http://$ip:${server.localPort}"
        thread(name = "phone-login", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }.onFailure { Timber.w(it, "Phone login request failed") } }
            }
        }
        Timber.i("Phone login page running at %s", address)
        return address
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 15_000
            val input = sock.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val out = sock.getOutputStream()
            if (path.startsWith("/logo.png") && logo != null) {
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${logo.size}\r\n" +
                        "Cache-Control: max-age=86400\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(logo)
                out.flush()
                return
            }
            val page =
                if (method == "POST" && path.startsWith("/submit")) {
                    val body = ByteArray(length.coerceAtMost(16_384)).also { buf -> var read = 0; while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n } }
                    submit(parseForm(String(body)))
                } else {
                    formPage(null)
                }
            val bytes = page.toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }
    }

    @Synchronized
    private fun submit(form: Map<String, String>): String {
        val entered = form["code"].orEmpty().filter { it.isDigit() }
        if (entered != code) {
            wrongAttempts++
            if (wrongAttempts >= 5) {
                code = newCode()
                wrongAttempts = 0
                return formPage("Too many wrong codes. A new code is showing on your TV.")
            }
            return formPage("That code doesn't match the one on your TV.")
        }
        val server = form["server"].orEmpty().trim()
        val username = form["username"].orEmpty().trim()
        val password = form["password"].orEmpty()
        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) return formPage("Fill in the server, username and password.")
        code = newCode()
        wrongAttempts = 0
        onDetails(server, username, password)
        return donePage()
    }

    private fun formPage(error: String?): String =
        """
        <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>AxiomTV sign in</title><style>
        body{margin:0;font-family:system-ui,sans-serif;background:#0F1012;color:#F2F2F2;display:flex;justify-content:center}
        main{width:100%;max-width:420px;padding:28px 20px}
        .logo{display:block;width:55%;max-width:220px;margin:0 auto 8px}
        h1{font-size:30px;margin:0 0 6px;text-align:center}h1 span{color:#E5A00D}p{color:#9AA0A6;margin:0 0 22px;text-align:center}
        .pw{position:relative}.pw input{padding-right:56px}
        .eye{position:absolute;right:6px;top:50%;transform:translateY(-50%);width:44px;height:44px;margin:0;padding:10px;background:none;border:0;color:#9AA0A6}
        .eye svg{width:24px;height:24px}
        label{display:block;font-size:14px;color:#9AA0A6;margin:14px 0 6px}
        input{width:100%;box-sizing:border-box;padding:14px;border-radius:10px;border:1px solid #2A2D33;background:#16181C;color:#fff;font-size:17px}
        input:focus{outline:none;border-color:#E5A00D}
        #code{font-size:26px;letter-spacing:10px;text-align:center}
        button{width:100%;margin-top:24px;padding:16px;border:0;border-radius:12px;background:#E5A00D;color:#1A1300;font-size:18px;font-weight:700}
        .err{background:#3A1515;color:#FFB4AB;padding:12px;border-radius:10px;margin-bottom:8px}
        </style></head><body><main>
        <img class="logo" src="/logo.png" alt=""><h1>Axiom<span>TV</span></h1><p>Enter the code on your TV, then your Xtream login.</p>
        ${if (error != null) "<div class=\"err\">$error</div>" else ""}
        <form method="post" action="/submit" autocomplete="on">
        <label for="code">Code on your TV</label><input id="code" name="code" inputmode="numeric" maxlength="4" required>
        <label for="server">Server address</label><input id="server" name="server" type="url" placeholder="http://example.com:8080" autocapitalize="off" required>
        <label for="username">Username</label><input id="username" name="username" autocapitalize="off" autocorrect="off" required>
        <label for="password">Password</label>
        <div class="pw"><input id="password" name="password" type="password" required>
        <button type="button" class="eye" aria-label="Show password" onclick="var p=document.getElementById('password');var s=p.type==='password';p.type=s?'text':'password';this.setAttribute('aria-label',s?'Hide password':'Show password');this.querySelector('.slash').style.display=s?'inline':'none'">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/><line class="slash" x1="3" y1="3" x2="21" y2="21" style="display:none"/></svg>
        </button></div>
        <button type="submit">Sign in on TV</button>
        </form></main></body></html>
        """.trimIndent()

    private fun donePage(): String =
        """
        <!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>AxiomTV</title><style>body{margin:0;font-family:system-ui,sans-serif;background:#0F1012;color:#F2F2F2;display:flex;justify-content:center;text-align:center}
        main{max-width:420px;padding:60px 20px}h1{color:#E5A00D}p{color:#9AA0A6}</style></head>
        <body><main><h1>Sent to your TV</h1><p>Your TV is signing in now. If it shows an error, go back and check your details.</p></main></body></html>
        """.trimIndent()

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()

    companion object {
        private val PORTS = listOf(8088, 8089, 8090, 0)

        private fun newCode() = Random.nextInt(1000, 10000).toString()

        /** The box's address on the home network (Wi-Fi or Ethernet). */
        fun localIp(): String? =
            runCatching {
                NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                    ?.hostAddress
            }.getOrNull()
    }
}
