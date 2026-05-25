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
 * Controller documentation containing its APIs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ControllerDoc {

    /** Controller class name (fully qualified) */
    private String className;

    /** Controller simple name */
    private String name;

    /** Base path from class-level @RequestMapping */
    private String basePath;

    /** Description from class Javadoc */
    private String description;

    /** Tags / categories */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /** API endpoints in this controller */
    @Builder.Default
    private List<ApiDoc> apis = new ArrayList<>();

    /** Persistence metadata */
    private PersistMeta persistMeta;
}
