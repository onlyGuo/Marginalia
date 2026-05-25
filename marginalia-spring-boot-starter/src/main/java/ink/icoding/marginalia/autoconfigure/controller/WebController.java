package ink.icoding.marginalia.autoconfigure.controller;

import ink.icoding.marginalia.autoconfigure.config.MarginaliaProperties;
import ink.icoding.marginalia.autoconfigure.debugger.DebuggerService;
import ink.icoding.marginalia.core.model.*;
import ink.icoding.marginalia.core.service.MarginaliaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST controller for Marginalia Web UI and API.
 * All endpoints are prefixed with the configured path (default: /marginalia/).
 */
@RestController
public class WebController {

    private final MarginaliaService service;
    private final DebuggerService debugger;
    private final MarginaliaProperties properties;

    public WebController(MarginaliaService service, DebuggerService debugger, MarginaliaProperties properties) {
        this.service = service;
        this.debugger = debugger;
        this.properties = properties;
    }

    // ==================== UI ====================

    /**
     * Serve the main HTML UI.
     */
    @GetMapping(value = "${marginalia.prefix:/marginalia}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(getIndexHtml());
    }

    @GetMapping(value = "${marginalia.prefix:/marginalia}/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> indexSlash() {
        return index();
    }

    // ==================== Tree ====================

    @GetMapping("${marginalia.prefix:/marginalia}/api/tree")
    public ResponseEntity<List<DocTree>> getDocTree() {
        return ResponseEntity.ok(service.buildDocTree());
    }

    // ==================== Controllers ====================

    @GetMapping("${marginalia.prefix:/marginalia}/api/controllers")
    public ResponseEntity<List<ControllerDoc>> getAllControllers() {
        return ResponseEntity.ok(service.getAllControllers());
    }

    @GetMapping("${marginalia.prefix:/marginalia}/api/controllers/{className}")
    public ResponseEntity<ControllerDoc> getController(@PathVariable String className) {
        return service.getController(className)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== APIs ====================

    @GetMapping("${marginalia.prefix:/marginalia}/api/endpoints")
    public ResponseEntity<List<ApiDoc>> getAllApis() {
        return ResponseEntity.ok(service.getAllControllers().stream()
                .flatMap(c -> c.getApis().stream())
                .toList());
    }

    @GetMapping("${marginalia.prefix:/marginalia}/api/endpoints/{signature}")
    public ResponseEntity<ApiDoc> getApi(@PathVariable String signature) {
        return service.getApi(signature)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("${marginalia.prefix:/marginalia}/api/endpoints/{signature}")
    public ResponseEntity<ApiDoc> updateApi(@PathVariable String signature, @RequestBody ApiDoc api) {
        service.updateApi(api);
        return ResponseEntity.ok(api);
    }

    // ==================== Entities ====================

    @GetMapping("${marginalia.prefix:/marginalia}/api/entities")
    public ResponseEntity<List<EntityDoc>> getAllEntities() {
        return ResponseEntity.ok(service.getAllEntities());
    }

    @GetMapping("${marginalia.prefix:/marginalia}/api/entities/{fullName}")
    public ResponseEntity<EntityDoc> getEntity(@PathVariable String fullName) {
        return service.getEntity(fullName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("${marginalia.prefix:/marginalia}/api/entities/{fullName}")
    public ResponseEntity<EntityDoc> updateEntity(@PathVariable String fullName, @RequestBody EntityDoc entity) {
        service.updateEntity(entity);
        return ResponseEntity.ok(entity);
    }

    // ==================== Index ====================

    @GetMapping("${marginalia.prefix:/marginalia}/api/index")
    public ResponseEntity<List<IndexEntry>> getIndex() {
        return ResponseEntity.ok(service.getIndexEntries());
    }

    @PutMapping("${marginalia.prefix:/marginalia}/api/index/{controllerClass}")
    public ResponseEntity<IndexEntry> updateIndexEntry(@PathVariable String controllerClass,
                                                         @RequestBody IndexEntry entry) {
        entry.setControllerClass(controllerClass);
        service.updateIndexEntry(entry);
        return ResponseEntity.ok(entry);
    }

    // ==================== Rescan ====================

    @PostMapping("${marginalia.prefix:/marginalia}/api/rescan")
    public ResponseEntity<MarginaliaService.ScanResult> rescan() {
        return ResponseEntity.ok(service.forceRescan());
    }

    // ==================== Debugger ====================

    /**
     * Proxy an API request for testing/debugging.
     */
    @PostMapping("${marginalia.prefix:/marginalia}/api/debug")
    public ResponseEntity<DebuggerService.DebugResponse> debugRequest(@RequestBody DebugRequest request) {
        DebuggerService.DebugResponse response = debugger.execute(
                request.getMethod(),
                request.getUrl(),
                request.getHeaders(),
                request.getQueryParams(),
                request.getBody()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * SSE proxy endpoint for streaming APIs.
     */
    @GetMapping("${marginalia.prefix:/marginalia}/api/debug/sse")
    public SseEmitter debugSse(@RequestParam String url,
                                @RequestParam(required = false) String headers) {
        Map<String, String> headerMap = new HashMap<>();
        if (headers != null && !headers.isEmpty()) {
            for (String pair : headers.split(";")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) headerMap.put(kv[0].trim(), kv[1].trim());
            }
        }
        return debugger.executeSse(url, headerMap);
    }

    // ==================== Update Response Example ====================

    /**
     * Update an API's response example from a debug response.
     */
    @PostMapping("${marginalia.prefix:/marginalia}/api/endpoints/{signature}/update-example")
    public ResponseEntity<ApiDoc> updateResponseExample(@PathVariable String signature,
                                                          @RequestBody String example) {
        Optional<ApiDoc> apiOpt = service.getApi(signature);
        if (apiOpt.isEmpty()) return ResponseEntity.notFound().build();

        ApiDoc api = apiOpt.get();
        if (api.getResponse() == null) {
            api.setResponse(ResponseDoc.builder().build());
        }
        api.getResponse().setExample(example);
        service.updateApi(api);
        return ResponseEntity.ok(api);
    }

    // ==================== Request DTO ====================

    public record DebugRequest(
            String method,
            String url,
            Map<String, String> headers,
            Map<String, String> queryParams,
            String body
    ) {
        public String getMethod() {
            return method;
        }

        public String getUrl() {
            return url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public Map<String, String> getQueryParams() {
            return queryParams;
        }

        public String getBody() {
            return body;
        }
    }

    // ==================== HTML ====================

    private String getIndexHtml() {
        try (var is = getClass().getResourceAsStream("/static/marginalia/index.html")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // fallback
        }
        return "<html><body><h1>Marginalia</h1><p>UI not found. Please check your build.</p></body></html>";
    }
}
