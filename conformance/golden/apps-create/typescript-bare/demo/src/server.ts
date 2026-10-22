import { createServer, type IncomingMessage } from "node:http";

import { handle } from "@lingara/apps";

import { createApp } from "./app.js";

/** At most `limit` bytes of the raw body: the signature covers these exact bytes. */
function readBody(req: IncomingMessage, limit: number): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let length = 0;
    req.on("data", (chunk: Buffer) => {
      chunks.push(chunk);
      length += chunk.length;
      if (length >= limit) req.pause();
    });
    req.on("end", () => resolve(new Uint8Array(Buffer.concat(chunks).subarray(0, limit))));
    req.on("error", reject);
  });
}

const secret = process.env.LINGARA_APP_SECRET;
if (secret === undefined || secret === "") {
  console.error("LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`.");
  process.exit(1);
}
const app = createApp(secret);
const port = Number(process.env.PORT ?? "8787");

// The kit's core: method, size, signature, decode, dispatch, validate. This server only moves bytes.
createServer((req, res) => {
  void handle(app, { method: req.method ?? "", headers: req.headers, body: (limit) => readBody(req, limit) }).then((answer) => {
    res.writeHead(answer.status, answer.headers).end(answer.body);
  });
}).listen(port, "127.0.0.1", () => {
  console.log(`demo is listening on http://127.0.0.1:${port}/`);
});
