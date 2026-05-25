package ink.icoding.marginalia.autoconfigure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Marginalia.
 * Configurable via application.yaml under "marginalia.*"
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "marginalia")
public class MarginaliaProperties {

    /**
     * Enable/disable Marginalia auto-configuration.
     */
    private boolean enabled = true;

    /**
     * Web UI access prefix.
     */
    private String prefix = "/marginalia";

    /**
     * Base Java package to scan for controllers.
     * If empty, scans all packages.
     */
    private String basePackage = "";

    /**
     * Source directories to scan.
     * If empty, auto-detects from project structure.
     */
    private List<String> sourceDirs = new ArrayList<>();

    /**
     * Data directory for persisting documentation.
     * Default: ./marginalia-data
     */
    private String dataDir = "./marginalia-data";

    /**
     * Whether to auto-scan on application startup.
     */
    private boolean autoScan = true;

    /**
     * Whether to enable the debugger (API testing) feature.
     */
    private boolean debuggerEnabled = true;

    /**
     * Custom title displayed in the Web UI.
     */
    private String title = "Marginalia API Documentation";

    /**
     * Custom description displayed in the Web UI.
     */
    private String description = "";

    /**
     * Whether to show the "Try it out" / debugger panel.
     */
    private boolean tryItOutEnabled = true;
}
