package app;

import com.getlingara.apps.AppResponse;
import com.getlingara.apps.LingaraApp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/** Serves {{display_name}} on 127.0.0.1, on the kit's primitive: {@link LingaraApp#handle}. */
public final class Main {
  private Main() {}

  /** The kit does the method, size, signature, decode, dispatch and validation; this only moves bytes. */
  static void answer(LingaraApp app, HttpExchange exchange) throws IOException {
    AppResponse reply = app.handle(exchange.getRequestMethod(), exchange.getRequestHeaders(), exchange.getRequestBody());
    if (reply.contentType() != null) {
      exchange.getResponseHeaders().set("content-type", reply.contentType());
    }
    exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(reply.body());
    }
  }

  public static void main(String[] args) throws IOException {
    String secret = System.getenv("LINGARA_APP_SECRET");
    if (secret == null || secret.isEmpty()) {
      System.err.println("LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`.");
      System.exit(1);
    }
    LingaraApp app = Apps.create(secret.split(","));
    String port = System.getenv().getOrDefault("PORT", "{{port}}");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 0);
    server.createContext("/", exchange -> answer(app, exchange));
    server.start();
    System.out.println("{{name}} is listening on http://127.0.0.1:" + port + "/");
  }
}
