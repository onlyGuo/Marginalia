package ink.icoding.marginalia.core.util;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.Type;

import java.util.stream.Collectors;

/**
 * Utility for generating unique method signatures used as document IDs.
 */
public final class SignatureUtil {

    private SignatureUtil() {}

    /**
     * Generate a unique signature ID: ClassName#methodName(paramType1,paramType2)
     */
    public static String generateSignature(String className, MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
        return className + "#" + method.getNameAsString() + "(" + params + ")";
    }

    /**
     * Generate a simple signature from class and method info strings.
     */
    public static String generateSignature(String className, String methodName, String[] paramTypes) {
        String params = String.join(",", paramTypes);
        return className + "#" + methodName + "(" + params + ")";
    }

    /**
     * Sanitize a string for use as a file/directory name.
     */
    public static String sanitizeFileName(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_");
    }

    /**
     * Extract simple class name from fully qualified name.
     */
    public static String simpleClassName(String fullName) {
        if (fullName == null) return "";
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    /**
     * Get the first type argument from a generic type string like "ResponseEntity<List<User>>".
     * Returns the innermost type.
     */
    public static String extractInnerType(String genericType) {
        if (genericType == null) return null;
        int start = genericType.indexOf('<');
        int end = genericType.lastIndexOf('>');
        if (start >= 0 && end > start) {
            return genericType.substring(start + 1, end).trim();
        }
        return genericType;
    }

    /**
     * Normalize a path: ensure it starts with / and doesn't end with /
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path;
    }
}
