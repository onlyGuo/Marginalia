package ink.icoding.marginalia.core.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans source directories to find Java controller files.
 */
public class SourceScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceScanner.class);

    /**
     * Find all Java source files under the given source directories.
     */
    public List<Path> findSourceFiles(List<String> sourceDirs) {
        List<Path> files = new ArrayList<>();
        for (String dir : sourceDirs) {
            Path path = Paths.get(dir);
            if (!Files.exists(path)) {
                log.warn("Source directory does not exist: {}", dir);
                continue;
            }
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.error("Error scanning source directory: {}", dir, e);
            }
        }
        return files;
    }

    /**
     * Auto-detect source directories from the current project.
     * Looks for standard Maven/Gradle layouts.
     */
    public List<String> detectSourceDirs(String basePath) {
        List<String> dirs = new ArrayList<>();
        Path base = Paths.get(basePath);

        // Standard Maven layout
        Path srcMainJava = base.resolve("src/main/java");
        if (Files.exists(srcMainJava)) {
            dirs.add(srcMainJava.toString());
        }

        // Check for multi-module projects
        try {
            if (Files.exists(base)) {
                Files.list(base)
                        .filter(Files::isDirectory)
                        .filter(p -> p.resolve("src/main/java").toFile().exists())
                        .forEach(p -> dirs.add(p.resolve("src/main/java").toString()));
            }
        } catch (IOException e) {
            log.debug("Error detecting multi-module source dirs", e);
        }

        return dirs;
    }
}
