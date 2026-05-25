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
 * Tree structure for hierarchical documentation display.
 * Represents the directory tree in the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocTree {

    /** Node name (directory or controller name) */
    private String name;

    /** Node type: "directory", "controller", "api" */
    private String type;

    /** Associated ID (controller class name or API id) */
    private String id;

    /** Icon hint for UI */
    private String icon;

    /** Child nodes */
    @Builder.Default
    private List<DocTree> children = new ArrayList<>();
}
