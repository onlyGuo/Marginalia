package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity (DTO/VO/Model) documentation.
 * Automatically mapped from request/response body types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityDoc {

    /** Entity name (simple class name) */
    private String name;

    /** Fully qualified class name */
    private String fullName;

    /** Description from class Javadoc */
    private String description;

    /** Entity fields */
    @Builder.Default
    private List<FieldDoc> fields = new ArrayList<>();

    /** Super class name if any */
    private String superClass;

    /** Whether this is an enum */
    private boolean isEnum;

    /** Enum values if isEnum is true */
    @Builder.Default
    private List<String> enumValues = new ArrayList<>();

    /** Enum value documentation if isEnum is true */
    @Builder.Default
    private List<EnumValueDoc> enumValueDocs = new ArrayList<>();

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
