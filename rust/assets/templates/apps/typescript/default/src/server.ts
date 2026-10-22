import { createServer } from "node:http";

import { nodeHandler } from "@lingara/apps";

import { createApp } from "./app.js";

const secret = process.env.LINGARA_APP_SECRET;
if (secret === undefined || secret === "") {
  console.error("LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`.");
  process.exit(1);
}
const port = Number(process.env.PORT ?? "{{port}}");

// A node:http request listener: it reads the raw body, verifies, dispatches and replies.
createServer(nodeHandler(createApp(secret))).listen(port, "127.0.0.1", () => {
  console.log(`{{name}} is listening on http://127.0.0.1:${port}/`);
});
