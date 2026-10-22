package app;

import com.getlingara.apps.JdkHttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

/** Serves {{display_name}} on 127.0.0.1. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    String secret = System.getenv("LINGARA_APP_SECRET");
    if (secret == null || secret.isEmpty()) {
      System.err.println("LINGARA_APP_SECRET is not set: run this app with `lingara apps dev`.");
      System.exit(1);
    }
    String port = System.getenv().getOrDefault("PORT", "{{port}}");
    // The JDK's own server: the handler reads the raw body, verifies it, then dispatches.
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 0);
    server.createContext("/", JdkHttpHandler.of(Apps.create(secret.split(","))));
    server.start();
    System.out.println("{{name}} is listening on http://127.0.0.1:" + port + "/");
  }
}
