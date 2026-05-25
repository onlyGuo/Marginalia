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
 * Represents a single entry in the index.json file.
 * Maps controller classes to their display names and categories.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndexEntry {

    /** Controller class name (fully qualified) */
    private String controllerClass;

    /** Display name in UI */
    private String displayName;

    /** Category / folder grouping */
    private String category;

    /** Sort order within the category */
    private int order;

    /** Tags for filtering */
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
