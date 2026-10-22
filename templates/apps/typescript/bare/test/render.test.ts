import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { test } from "node:test";

import { handle } from "@lingara/apps";

import { createApp } from "../src/app.js";

// A visibly fake secret: lgr_whsec_ + base64 of 32 ASCII bytes.
const SECRET = `lgr_whsec_${Buffer.from("template-offline-test-secret-01!").toString("base64")}`;

/** Standard Webhooks, as Lingara signs: v1,<base64 HMAC-SHA256 over id.timestamp.body>. */
function sign(body: string): Record<string, string> {
  const id = "lgr_msg_0123456789abcdef0123456789abcdef";
  const timestamp = String(Math.floor(Date.now() / 1000));
  const key = Buffer.from(SECRET.slice("lgr_whsec_".length), "base64");
  const signature = createHmac("sha256", key).update(`${id}.${timestamp}.${body}`).digest("base64");
  return { "content-type": "application/json", "webhook-id": id, "webhook-timestamp": timestamp, "webhook-signature": `v1,${signature}` };
}

test("a signed render returns a card", async () => {
  const body = JSON.stringify({
    type: "app.render",
    id: "lgr_msg_0123456789abcdef0123456789abcdef",
    install_id: "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
    subject: "lgr_sub_template",
    slot: "home.side",
    locale: "zh-Hans",
    context: [],
  });
  const bytes = new TextEncoder().encode(body);
  const answer = await handle(createApp(SECRET), { method: "POST", headers: sign(body), body: async () => bytes });
  assert.equal(answer.status, 200);
  const reply = JSON.parse(new TextDecoder().decode(answer.body)) as { card?: unknown };
  assert.ok(reply.card, "the reply carries a card");
});
