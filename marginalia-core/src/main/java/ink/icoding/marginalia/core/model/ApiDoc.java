package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API endpoint documentation model.
 * Uses method signature as the unique identifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiDoc {

    /** Unique ID based on method signature: ClassName#methodName(paramTypes...) */
    private String id;

    /** HTTP method: GET, POST, PUT, DELETE, PATCH */
    private String httpMethod;

    /** Full URL path, e.g. /api/users/{id} */
    private String path;

    /** API name, from Javadoc or method name */
    private String name;

    /** API description from Javadoc */
    private String description;

    /** Controller class name (fully qualified) */
    private String controllerClass;

    /** Controller simple name */
    private String controllerName;

    /** Method name in source code */
    private String methodName;

    /** Method signature for unique indexing */
    private String methodSignature;

    /** Content-Type, default application/json */
    private String contentType;

    /** Path variables */
    @Builder.Default
    private List<EndpointParam> pathVariables = new ArrayList<>();

    /** Query parameters */
    @Builder.Default
    private List<EndpointParam> queryParams = new ArrayList<>();

    /** Header parameters */
    @Builder.Default
    private List<EndpointParam> headerParams = new ArrayList<>();

    /** Cookie parameters */
    @Builder.Default
    private List<EndpointParam> cookieParams = new ArrayList<>();

    /** Form data parameters */
    @Builder.Default
    private List<EndpointParam> formParams = new ArrayList<>();

    /** Request body */
    private RequestBodyDoc requestBody;

    /** Response body */
    private ResponseDoc response;

    /** Tags for categorization */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** Whether this endpoint produces SSE */
    private boolean sse;

    /** Deprecation flag */
    private boolean deprecated;

    // ==================== Debug Mode State ====================

    /** Debug mode: HTTP method override */
    private String debugMethod;

    /** Debug mode: URL path override */
    private String debugUrl;

    /** Debug mode: custom headers */
    @Builder.Default
    private Map<String, String> debugHeaders = new LinkedHashMap<>();

    /** Debug mode: custom query parameters */
    @Builder.Default
    private Map<String, String> debugParams = new LinkedHashMap<>();

    /** Debug mode: header descriptions */
    @Builder.Default
    private Map<String, String> debugHeaderDescs = new LinkedHashMap<>();

    /** Debug mode: param descriptions */
    @Builder.Default
    private Map<String, String> debugParamDescs = new LinkedHashMap<>();

    /** Debug mode: request body content */
    private String debugBody;

    /** Debug mode: last response body */
    private String debugResponse;

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
