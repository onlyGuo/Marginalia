package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single API endpoint parameter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EndpointParam {

    /** Parameter name */
    private String name;

    /** Parameter type (simple name) */
    private String type;

    /** Fully qualified type name */
    private String fullType;

    /** Description from comments */
    private String description;

    /** Whether this parameter is required */
    @Builder.Default
    private boolean required = true;

    /** Default value if optional */
    private String defaultValue;

    /** Example value for documentation */
    private String example;

    /** Allowed values or enum options */
    private String allowableValues;

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
