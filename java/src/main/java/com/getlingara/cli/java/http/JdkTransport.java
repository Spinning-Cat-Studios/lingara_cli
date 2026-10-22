package com.getlingara.cli.java.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code java.net.http.HttpClient}, the JDK's own client (ADR 16.10.26af D1), never the {@code
 * lingara} API library, whose User-Agent and retry policy are not the CLI's.
 *
 * <p>HTTP/1.1 only, so no {@code h2c} upgrade header reaches a plain-HTTP server, and no
 * redirects: an unexpected 3xx is B2 rule 8's to classify.
 */
public final class JdkTransport implements Wire.Transport {
    private HttpClient client;
    private final Duration timeout;

    public JdkTransport(Duration timeout) {
        this.timeout = timeout;
    }

    /** Built on the first request, so a run that sends none starts no selector thread. */
    private HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return client;
    }

    public JdkTransport() {
        this(Duration.ofSeconds(60));
    }

    @Override
    public Wire.In send(Wire.Out request) throws IOException {
        HttpRequest.BodyPublisher body = request.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(timeout)
                .method(request.method(), body);
        for (String[] h : request.headers()) {
            builder.setHeader(h[0], h[1]);
        }
        try {
            HttpResponse<byte[]> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            List<String[]> headers = new ArrayList<>();
            response.headers().map().forEach((k, values) -> values.forEach(v -> headers.add(new String[] {k, v})));
            return new Wire.In(response.statusCode(), headers, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
