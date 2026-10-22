// `login`'s default: PKCE over a loopback redirect (RFC 8252, 16.10.26u §6).
// The kit binds `127.0.0.1:0`, opens the portal's authorize page, and accepts
// exactly one callback whose `state` matches and whose `iss` is the portal
// base (RFC 9207). The listener gives up after 5 minutes, and a Ctrl+C while
// it waits returns `interrupted` (ADR 16.10.26af D9).
package com.getlingara.cli.kotlin.auth

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Signals
import com.getlingara.cli.kotlin.http.Fixed
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val LISTEN_MILLIS = 300_000L
private const val CLOSE_TAB = "<!doctype html><title>Lingara</title><p>You can close this tab.</p>"

/** What the browser brought back to the loopback: a code, or a decline (no code). */
private class Callback(val code: String?)

object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun randomText(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(encoder::encodeToString)

    fun challenge(verifier: String): String = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** `application/x-www-form-urlencoded` pairs: `+` is a space, `%XX` a byte. */
    fun parseQuery(query: String): List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map { p ->
        val (k, v) = if ('=' in p) p.substringBefore('=') to p.substringAfter('=') else p to ""
        URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }
}

/** The token response for the code the browser brought back. */
fun Ctx.browserLogin(): JsonNode {
    val listener = try {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    } catch (e: IOException) {
        throw Fail.failure("could not listen on 127.0.0.1: ${e.message}")
    }
    listener.use {
        val (verifier, state) = Pkce.randomText() to Pkce.randomText()
        val redirect = "http://127.0.0.1:${listener.localPort}/callback"
        val clientId = app.endpoints.clientId
        val query = listOf(
            "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect,
            "code_challenge" to Pkce.challenge(verifier), "code_challenge_method" to "S256", "state" to state, "scope" to SCOPES,
        )
        val url = app.http.url(Fixed.AUTHORIZE.route, emptyList(), query)
        out.note(url)
        out.note(Assets.message("login_browser_wait"))
        app.browser(url)
        val issuer = app.http.bases.portal.trimEnd('/')
        val code = Signals.cooperative { waitForCallback(listener, state, issuer) }.code
            ?: throw Fail(Exit.AUTH_REQUIRED, "access_denied", "sign-in was declined")
        val form = listOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "code_verifier" to verifier, "client_id" to clientId)
        return tokenPost(Fixed.TOKEN, form)
    }
}

private fun waitForCallback(listener: ServerSocket, state: String, issuer: String): Callback {
    listener.soTimeout = 200
    val deadline = System.currentTimeMillis() + LISTEN_MILLIS
    while (System.currentTimeMillis() < deadline) {
        Signals.check()
        val stream = try {
            listener.accept()
        } catch (_: SocketTimeoutException) {
            continue
        } catch (e: IOException) {
            throw Fail.failure("the loopback listener failed: ${e.message}")
        }
        answer(stream, state, issuer)?.let { return it }
    }
    throw Fail.auth("no sign-in arrived within 5 minutes; run `lingara login` again")
}

/** Answers one request on the loopback; the callback, if it is the one. */
private fun answer(stream: Socket, state: String, issuer: String): Callback? = stream.use { s ->
    s.soTimeout = 5000
    val head = runCatching { String(s.getInputStream().readNBytesUntilHead(), Charsets.UTF_8) }.getOrDefault("")
    val target = head.split(Regex("\\s+")).getOrElse(1) { "" }
    val params = Pkce.parseQuery(target.substringAfter('?', ""))
    val param = { k: String -> params.firstOrNull { it.first == k }?.second }
    val ours = target.substringBefore('?') == "/callback" && param("state") == state && param("iss")?.trimEnd('/') == issuer
    val status = if (ours) "200 OK" else "400 Bad Request"
    val reply = "HTTP/1.1 $status\r\ncontent-type: text/html\r\ncontent-length: ${CLOSE_TAB.length}\r\nconnection: close\r\n\r\n$CLOSE_TAB"
    runCatching { s.getOutputStream().apply { write(reply.toByteArray()); flush() } }
    if (ours) Callback(param("code")) else null
}

/** One read of up to 8 KiB: the request line is in the first packet the browser sends. */
private fun InputStream.readNBytesUntilHead(): ByteArray {
    val buf = ByteArray(8192)
    val n = read(buf)
    return if (n <= 0) ByteArray(0) else buf.copyOf(n)
}
