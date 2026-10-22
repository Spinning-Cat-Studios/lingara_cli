package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.getlingara.apps.AppResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class RenderTest {
  // A visibly fake key: the secret is lgr_whsec_ + its base64.
  private static final byte[] KEY = "template-offline-test-secret-01!".getBytes(StandardCharsets.US_ASCII);

  @Test
  void aSignedRenderReturnsACard() throws Exception {
    String id = "lgr_msg_0123456789abcdef0123456789abcdef";
    String body =
        "{\"type\":\"app.render\",\"id\":\"" + id + "\",\"install_id\":\"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\","
            + "\"subject\":\"lgr_sub_template\",\"slot\":\"home.side\",\"locale\":\"zh-Hans\",\"context\":[]}";
    String timestamp = Long.toString(System.currentTimeMillis() / 1000);
    // Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>.
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    String signature = Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    Map<String, List<String>> headers =
        Map.of("webhook-id", List.of(id), "webhook-timestamp", List.of(timestamp), "webhook-signature", List.of("v1," + signature));
    AppResponse reply =
        Apps.create("lgr_whsec_" + Base64.getEncoder().encodeToString(KEY))
            .handle("POST", headers, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    assertEquals(200, reply.status());
    assertTrue(new String(reply.body(), StandardCharsets.UTF_8).contains("\"card\""), "the reply carries a card");
  }
}
