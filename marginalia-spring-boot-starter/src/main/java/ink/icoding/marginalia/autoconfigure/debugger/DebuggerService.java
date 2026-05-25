package ink.icoding.marginalia.autoconfigure.debugger;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for proxying API requests for the debugger/try-it-out feature.
 * Supports all HTTP methods including SSE via java.net.http.HttpClient.
 */
public class DebuggerService {

    private final HttpClient httpClient;
    private final ExecutorService sseExecutor;

    public DebuggerService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.sseExecutor = Executors.newCachedThreadPool();
    }

    /**
     * Execute an API request and return the response.
     */
    public DebugResponse execute(String method, String url, Map<String, String> headers,
                                   Map<String, String> queryParams, String body) {
        try {
            // Add query params to URL
            if (queryParams != null && !queryParams.isEmpty()) {
                StringBuilder sb = new StringBuilder(url);
                sb.append(url.contains("?") ? "&" : "?");
                queryParams.forEach((k, v) -> sb.append(k).append("=").append(v).append("&"));
                url = sb.substring(0, sb.length() - 1);
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5));

            // Set headers
            if (headers != null) {
                headers.forEach(reqBuilder::header);
            }

            // Set method and body
            switch (method.toUpperCase()) {
                case "GET" -> reqBuilder.GET();
                case "DELETE" -> reqBuilder.DELETE();
                case "POST" -> reqBuilder.POST(bodyPublisher(body));
                case "PUT" -> reqBuilder.PUT(bodyPublisher(body));
                case "PATCH" -> reqBuilder.method("PATCH", bodyPublisher(body));
                default -> reqBuilder.method(method.toUpperCase(), bodyPublisher(body));
            }

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, String> responseHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) responseHeaders.put(k, v.get(0));
            });

            return new DebugResponse(
                    response.statusCode(),
                    responseHeaders,
                    response.body(),
                    elapsed,
                    null
            );
        } catch (Exception e) {
            return new DebugResponse(0, Map.of(), null, 0, e.getMessage());
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(String body) {
        if (body == null || body.isEmpty()) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofString(body);
    }

    /**
     * Create an SSE emitter for streaming API responses.
     */
    public SseEmitter executeSse(String url, Map<String, String> headers) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        sseExecutor.submit(() -> {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .timeout(Duration.ofHours(1));

                if (headers != null) {
                    headers.forEach(reqBuilder::header);
                }

                HttpResponse<java.io.InputStream> response =
                        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

                try (java.io.InputStream is = response.body();
                     java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    String line;
                    StringBuilder eventBuilder = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!eventBuilder.isEmpty()) {
                                emitter.send(SseEmitter.event().data(eventBuilder.toString()));
                                eventBuilder.setLength(0);
                            }
                        } else if (line.startsWith("data:")) {
                            if (eventBuilder.length() > 0) eventBuilder.append("\n");
                            eventBuilder.append(line.substring(5).trim());
                        } else if (line.startsWith("event:")) {
                            // Could parse event name for future use
                        } else if (line.startsWith("id:")) {
                            // Could parse event id for future use
                        } else if (line.startsWith(":")) {
                            // Comment line, ignore
                        } else {
                            if (eventBuilder.length() > 0) eventBuilder.append("\n");
                            eventBuilder.append(line);
                        }
                    }
                }

                emitter.complete();
            } catch (java.io.InterruptedIOException e) {
                // Client disconnected
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * Response from a debug API call.
     */
    public record DebugResponse(
            int statusCode,
            Map<String, String> headers,
            String body,
            long elapsedMs,
            String error
    ) {}
}
