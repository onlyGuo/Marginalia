package ink.icoding.marginalia.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Metadata for tracking persistence state and user modifications.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PersistMeta {

    /** Whether this item has been modified by the user */
    @Builder.Default
    private boolean userModified = false;

    /** Timestamp of last modification */
    private LocalDateTime lastModified;

    /** Timestamp of last auto-generation */
    private LocalDateTime lastGenerated;

    /** Version counter for conflict detection */
    @Builder.Default
    private long version = 1;

    /** Fields that have been explicitly modified by the user (field-level tracking) */
    private java.util.Set<String> modifiedFields;
}
