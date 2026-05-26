package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Documentation for a single field in an entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldDoc {

    /** Field name */
    private String name;

    /** Field type (simple name) */
    private String type;

    /** Fully qualified type */
    private String fullType;

    /** Entity reference name (links to EntityDoc) */
    private String entityRef;

    /** Description from Javadoc or comments */
    private String description;

    /** Example value */
    private String example;

    /** Whether the field is required */
    @Builder.Default
    private boolean required = true;

    /** Default value */
    private String defaultValue;

    /** Minimum value (for numbers) */
    private String minimum;

    /** Maximum value (for numbers) */
    private String maximum;

    /** Allowed values / enum options */
    private String allowableValues;

    /** Whether the field is deprecated */
    private boolean deprecated;

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
