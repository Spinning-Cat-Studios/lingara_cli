package app

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RenderTest {
    // A visibly fake key: the secret is lgr_whsec_ + its base64.
    private val key = "template-offline-test-secret-01!".toByteArray(Charsets.US_ASCII)

    @Test
    fun aSignedRenderReturnsACard() {
        val id = "lgr_msg_0123456789abcdef0123456789abcdef"
        val body =
            """{"type":"app.render","id":"$id","install_id":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",""" +
                """"subject":"lgr_sub_template","slot":"home.side","locale":"zh-Hans","context":[]}"""
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        // Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val signature = Base64.getEncoder().encodeToString(mac.doFinal("$id.$timestamp.$body".toByteArray()))
        val headers = mapOf("webhook-id" to listOf(id), "webhook-timestamp" to listOf(timestamp), "webhook-signature" to listOf("v1,$signature"))
        val app = createApp("lgr_whsec_" + Base64.getEncoder().encodeToString(key))
        val reply = runBlocking { app.handle("POST", headers) { body.toByteArray() } }
        assertEquals(200, reply.status)
        assertTrue(String(reply.body).contains("\"card\""), "the reply carries a card")
    }
}
