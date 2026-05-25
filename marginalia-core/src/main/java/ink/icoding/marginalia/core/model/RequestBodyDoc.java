package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body documentation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequestBodyDoc {

    /** Body type (simple name) */
    private String type;

    /** Fully qualified type name */
    private String fullType;

    /** Description from comments */
    private String description;

    /** Whether request body is required */
    @Builder.Default
    private boolean required = true;

    /** Content type, default application/json */
    @Builder.Default
    private String contentType = "application/json";

    /** Example JSON string */
    private String example;

    /** If the type is a generic, the actual type arguments */
    private String genericType;

    /** Entity reference name (links to EntityDoc) */
    private String entityRef;

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
