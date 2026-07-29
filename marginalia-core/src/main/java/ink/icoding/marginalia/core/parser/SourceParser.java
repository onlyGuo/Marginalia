package ink.icoding.marginalia.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import ink.icoding.marginalia.core.model.*;
import ink.icoding.marginalia.core.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses Java source files to extract controller and entity documentation.
 *
 * Comment priority: Javadoc > Line comments > Swagger annotations > simple signature
 */
public class SourceParser {

    private static final Logger log = LoggerFactory.getLogger(SourceParser.class);

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping"
    );

    private static final Map<String, String> MAPPING_METHOD_MAP = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "GET"
    );

    private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "double", "float", "boolean", "byte", "short", "char",
            "void", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "String", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime",
            "Date", "UUID", "MultipartFile", "InputStream", "OutputStream", "byte[]"
    );

    public SourceParser() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    /**
     * Parse a single Java source file and extract controller info.
     * Returns empty list if the file doesn't contain a controller.
     */
    public List<ControllerDoc> parseSourceFile(Path file, String basePackage) {
        try {
            String content = Files.readString(file);
            CompilationUnit cu = StaticJavaParser.parse(content);

            List<ControllerDoc> controllers = new ArrayList<>();

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                if (isController(clazz, basePackage)) {
                    try {
                        ControllerDoc doc = parseController(clazz, cu);
                        if (doc != null && !doc.getApis().isEmpty()) {
                            controllers.add(doc);
                        }
                    } catch (Exception e) {
                        log.warn("Error parsing controller: {}", clazz.getNameAsString(), e);
                    }
                }
            });

            return controllers;
        } catch (Exception e) {
            log.error("Error parsing source file: {}", file, e);
            return List.of();
        }
    }

    private boolean isController(ClassOrInterfaceDeclaration clazz, String basePackage) {
        // Must be a class (not interface), unless interface has @RestController
        if (clazz.isInterface()) return false;

        // Check for controller annotations
        boolean hasControllerAnnotation = clazz.getAnnotations().stream()
                .anyMatch(a -> CONTROLLER_ANNOTATIONS.contains(a.getNameAsString()));

        if (!hasControllerAnnotation) return false;

        // If base package specified, check it
        if (basePackage != null && !basePackage.isEmpty()) {
            Optional<CompilationUnit> cu = clazz.findCompilationUnit();
            if (cu.isPresent()) {
                Optional<String> pkg = cu.get().getPackageDeclaration().map(pd -> pd.getNameAsString());
                if (pkg.isPresent() && !pkg.get().startsWith(basePackage)) {
                    return false;
                }
            }
        }

        return true;
    }

    private ControllerDoc parseController(ClassOrInterfaceDeclaration clazz, CompilationUnit cu) {
        String className = getFullyQualifiedClassName(clazz, cu);
        String basePath = getClassBasePath(clazz);
        String description = extractDescription(clazz);
        List<String> tags = extractTags(clazz);

        ControllerDoc.ControllerDocBuilder builder = ControllerDoc.builder()
                .className(className)
                .name(clazz.getNameAsString())
                .basePath(basePath)
                .description(description)
                .tags(tags);

        List<ApiDoc> apis = new ArrayList<>();
        for (MethodDeclaration method : clazz.getMethods()) {
            if (hasMappingAnnotation(method)) {
                try {
                    ApiDoc api = parseApiMethod(method, className, basePath, clazz);
                    if (api != null) {
                        apis.add(api);
                    }
                } catch (Exception e) {
                    log.warn("Error parsing method: {}#{}", clazz.getNameAsString(), method.getNameAsString(), e);
                }
            }
        }

        builder.apis(apis);
        return builder.build();
    }

    private ApiDoc parseApiMethod(MethodDeclaration method, String className, String basePath,
                                   ClassOrInterfaceDeclaration clazz) {
        // Extract mapping info
        MappingInfo mapping = extractMappingInfo(method);
        if (mapping == null) return null;

        String fullPath = combinePaths(basePath, mapping.path);
        fullPath = SignatureUtil.normalizePath(fullPath);

        // Build method signature
        String[] paramTypes = method.getParameters().stream()
                .map(p -> p.getType().asString())
                .toArray(String[]::new);
        String signature = SignatureUtil.generateSignature(className, method);
        String methodId = SignatureUtil.sanitizeFileName(signature);

        // Extract description with priority: Javadoc > line comment > Swagger > method name
        String description = extractMethodDescription(method);
        String name = extractMethodName(method, description);

        // Parse parameters
        List<EndpointParam> pathVars = new ArrayList<>();
        List<EndpointParam> queryParams = new ArrayList<>();
        List<EndpointParam> headerParams = new ArrayList<>();
        List<EndpointParam> cookieParams = new ArrayList<>();
        List<EndpointParam> formParams = new ArrayList<>();
        RequestBodyDoc requestBody = null;
        ResponseDoc response = null;

        for (com.github.javaparser.ast.body.Parameter param : method.getParameters()) {
            // Skip HttpServletRequest/Response types
            String paramType = param.getType().asString();
            if (isServletType(paramType)) continue;

            EndpointParam ep = parseParameter(param);

            if (hasAnnotation(param, "PathVariable")) {
                pathVars.add(ep);
            } else if (hasAnnotation(param, "RequestParam")) {
                queryParams.add(ep);
            } else if (hasAnnotation(param, "RequestHeader")) {
                headerParams.add(ep);
            } else if (hasAnnotation(param, "CookieValue")) {
                cookieParams.add(ep);
            } else if (hasAnnotation(param, "ModelAttribute")) {
                formParams.add(ep);
            } else if (hasAnnotation(param, "RequestBody")) {
                requestBody = parseRequestBody(param);
            } else {
                // Default: if it's a simple type -> query param, if complex -> request body
                if (isSimpleType(paramType)) {
                    queryParams.add(ep);
                } else {
                    requestBody = parseRequestBody(param);
                }
            }
        }

        // Parse response
        Type returnType = method.getType();
        String returnTypeName = returnType.asString();
        boolean isSse = returnTypeName.contains("SseEmitter") ||
                returnTypeName.contains("Flux") ||
                returnTypeName.contains("ServerSentEvent");

        response = ResponseDoc.builder()
                .type(SignatureUtil.simpleClassName(unboxGenericType(returnTypeName)))
                .fullType(resolveFullType(returnType, clazz.findCompilationUnit().orElse(null)))
                .genericType(returnTypeName.contains("<") ? returnTypeName : null)
                .build();

        return ApiDoc.builder()
                .id(methodId)
                .httpMethod(mapping.httpMethod)
                .path(fullPath)
                .name(name)
                .description(description)
                .controllerClass(className)
                .controllerName(clazz.getNameAsString())
                .methodName(method.getNameAsString())
                .methodSignature(signature)
                .contentType(mapping.contentType)
                .pathVariables(pathVars)
                .queryParams(queryParams)
                .headerParams(headerParams)
                .cookieParams(cookieParams)
                .formParams(formParams)
                .requestBody(requestBody)
                .response(response)
                .tags(extractMethodTags(method))
                .sse(isSse)
                .deprecated(hasAnnotation(method, "Deprecated"))
                .build();
    }

    /**
     * Parse an entity class to extract field documentation.
     */
    public EntityDoc parseEntity(Path file, String entityClassName) {
        try {
            String content = Files.readString(file);
            CompilationUnit cu = StaticJavaParser.parse(content);

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (clazz.getNameAsString().equals(SignatureUtil.simpleClassName(entityClassName))) {
                    return parseEntityClass(clazz, cu);
                }
            }

            for (RecordDeclaration record : cu.findAll(RecordDeclaration.class)) {
                if (record.getNameAsString().equals(SignatureUtil.simpleClassName(entityClassName))) {
                    return parseRecordClass(record, cu);
                }
            }

            // Check enums
            for (EnumDeclaration enumDecl : cu.findAll(EnumDeclaration.class)) {
                if (enumDecl.getNameAsString().equals(SignatureUtil.simpleClassName(entityClassName))) {
                    return parseEnumClass(enumDecl, cu);
                }
            }
        } catch (Exception e) {
            log.error("Error parsing entity file: {}", file, e);
        }
        return null;
    }

    /**
     * Parse entity from source content string directly.
     */
    public EntityDoc parseEntityFromContent(String content, String entityClassName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            return parseEntityFromCu(cu, entityClassName);
        } catch (Exception e) {
            log.error("Error parsing entity content", e);
        }
        return null;
    }

    /**
     * Parse an entity class from an already-parsed CompilationUnit.
     */
    public EntityDoc parseEntityFromCu(CompilationUnit cu, String simpleClassName) {
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (clazz.getNameAsString().equals(simpleClassName)) {
                return parseEntityClass(clazz, cu);
            }
        }
        for (RecordDeclaration record : cu.findAll(RecordDeclaration.class)) {
            if (record.getNameAsString().equals(simpleClassName)) {
                return parseRecordClass(record, cu);
            }
        }
        for (EnumDeclaration enumDecl : cu.findAll(EnumDeclaration.class)) {
            if (enumDecl.getNameAsString().equals(simpleClassName)) {
                return parseEnumClass(enumDecl, cu);
            }
        }
        return null;
    }

    /**
     * Resolve a simple class name to its fully qualified name using the imports in a CompilationUnit.
     * Returns null if not found.
     */
    public String resolveImport(CompilationUnit cu, String simpleClassName) {
        if (cu == null || simpleClassName == null) return null;
        for (var imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                String fqn = imp.getNameAsString();
                if (fqn.endsWith("." + simpleClassName)) {
                    return fqn;
                }
            }
        }
        // Check same package
        if (cu.getPackageDeclaration().isPresent()) {
            return cu.getPackageDeclaration().get().getNameAsString() + "." + simpleClassName;
        }
        return simpleClassName;
    }

    /**
     * Get the CompilationUnit from a source file.
     */
    public CompilationUnit parseFile(Path file) {
        try {
            return StaticJavaParser.parse(file);
        } catch (Exception e) {
            log.error("Error parsing file: {}", file, e);
            return null;
        }
    }

    private EntityDoc parseEntityClass(ClassOrInterfaceDeclaration clazz, CompilationUnit cu) {
        String fullName = getFullyQualifiedClassName(clazz, cu);
        String description = extractDescription(clazz);
        String superClass = clazz.getExtendedTypes().isEmpty() ? null :
                clazz.getExtendedTypes().get(0).asString();

        List<FieldDoc> fields = new ArrayList<>();
        // Parse fields
        for (FieldDeclaration field : clazz.getFields()) {
            if (field.isStatic()) continue;
            for (VariableDeclarator var : field.getVariables()) {
                fields.add(parseField(field, var, cu));
            }
        }

        return EntityDoc.builder()
                .name(clazz.getNameAsString())
                .fullName(fullName)
                .description(description)
                .fields(fields)
                .superClass(superClass)
                .isEnum(false)
                .build();
    }

    private EntityDoc parseRecordClass(RecordDeclaration record, CompilationUnit cu) {
        List<FieldDoc> fields = record.getParameters().stream()
                .map(component -> parseRecordComponent(record, component, cu))
                .toList();

        return EntityDoc.builder()
                .name(record.getNameAsString())
                .fullName(getFullyQualifiedClassName(record, cu))
                .description(extractDescription(record))
                .fields(fields)
                .isEnum(false)
                .build();
    }

    private FieldDoc parseRecordComponent(RecordDeclaration record,
                                           com.github.javaparser.ast.body.Parameter component,
                                           CompilationUnit cu) {
        String description = extractRecordComponentDescription(record, component.getNameAsString());
        if (description == null || description.isEmpty()) {
            description = extractSwaggerDescription(component);
        }
        String example = extractAnnotationValue(component, "Schema", "example");
        if (example == null) {
            example = extractAnnotationValue(component, "ApiModelProperty", "example");
        }

        boolean required = !hasAnnotation(component, "Nullable") &&
                extractAnnotationBoolean(component, "Schema", "required").orElse(true);

        return FieldDoc.builder()
                .name(component.getNameAsString())
                .type(component.getType().asString())
                .fullType(resolveFullType(component.getType(), cu))
                .description(description)
                .example(example)
                .required(required)
                .deprecated(hasAnnotation(component, "Deprecated"))
                .build();
    }

    private EntityDoc parseEnumClass(EnumDeclaration enumDecl, CompilationUnit cu) {
        String fullName = getFullyQualifiedClassName(enumDecl, cu);
        String description = extractDescription(enumDecl);
        List<String> values = enumDecl.getEntries().stream()
                .map(EnumConstantDeclaration::getNameAsString)
                .toList();
        List<EnumValueDoc> valueDocs = enumDecl.getEntries().stream()
                .map(entry -> EnumValueDoc.builder()
                        .name(entry.getNameAsString())
                        .description(extractEnumValueDescription(entry))
                        .build())
                .toList();

        return EntityDoc.builder()
                .name(enumDecl.getNameAsString())
                .fullName(fullName)
                .description(description)
                .isEnum(true)
                .enumValues(values)
                .enumValueDocs(valueDocs)
                .fields(List.of())
                .build();
    }

    private FieldDoc parseField(FieldDeclaration field, VariableDeclarator var, CompilationUnit cu) {
        String description = extractDescription(field);
        // If no field-level comment, check for inline comment after declaration
        if (description == null || description.isEmpty()) {
            description = extractInlineComment(field);
        }

        // Extract Swagger annotations
        String swaggerDesc = extractSwaggerDescription(field);
        String example = extractAnnotationValue(field, "Schema", "example");
        if (example == null) example = extractAnnotationValue(field, "ApiModelProperty", "example");

        // Priority: Javadoc > line comment > Swagger > simple type
        if (description == null || description.isEmpty()) {
            description = swaggerDesc;
        }

        boolean required = !hasAnnotation(field, "Nullable") &&
                extractAnnotationBoolean(field, "Schema", "required").orElse(true);

        return FieldDoc.builder()
                .name(var.getNameAsString())
                .type(var.getType().asString())
                .fullType(resolveFullType(var.getType(), cu))
                .description(description)
                .example(example)
                .required(required)
                .deprecated(hasAnnotation(field, "Deprecated"))
                .build();
    }

    private EndpointParam parseParameter(com.github.javaparser.ast.body.Parameter param) {
        String description = extractParamDescription(param);
        String swaggerDesc = extractSwaggerDescription(param);

        if (description == null || description.isEmpty()) {
            description = swaggerDesc;
        }

        String name = param.getNameAsString();
        // Override name from annotation if present
        String annoName = extractAnnotationValue(param, "RequestParam", "value");
        if (annoName == null) annoName = extractAnnotationValue(param, "RequestParam", "name");
        if (annoName == null) annoName = extractAnnotationValue(param, "PathVariable", "value");
        if (annoName == null) annoName = extractAnnotationValue(param, "PathVariable", "name");
        if (annoName != null && !annoName.isEmpty()) name = annoName;

        boolean required = extractAnnotationBoolean(param, "RequestParam", "required").orElse(true);
        String defaultValue = extractAnnotationValue(param, "RequestParam", "defaultValue");

        return EndpointParam.builder()
                .name(name)
                .type(param.getType().asString())
                .fullType(param.getType().asString())
                .description(description)
                .required(required)
                .defaultValue(defaultValue)
                .build();
    }

    private RequestBodyDoc parseRequestBody(com.github.javaparser.ast.body.Parameter param) {
        String typeName = param.getType().asString();
        boolean required = !hasAnnotation(param, "Nullable") &&
                extractAnnotationBoolean(param, "RequestBody", "required").orElse(true);

        return RequestBodyDoc.builder()
                .type(SignatureUtil.simpleClassName(unboxGenericType(typeName)))
                .fullType(typeName)
                .required(required)
                .description(extractParamDescription(param))
                .build();
    }

    // ---- Mapping Info Extraction ----

    private boolean hasMappingAnnotation(MethodDeclaration method) {
        for (String annoName : MAPPING_ANNOTATIONS) {
            if (method.getAnnotationByName(annoName).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private record MappingInfo(String httpMethod, String path, String contentType) {}

    private MappingInfo extractMappingInfo(MethodDeclaration method) {
        for (String annoName : MAPPING_ANNOTATIONS) {
            Optional<AnnotationExpr> anno = method.getAnnotationByName(annoName);
            if (anno.isPresent()) {
                String httpMethod = MAPPING_METHOD_MAP.getOrDefault(annoName, "GET");
                String path = extractMappingPath(anno.get());
                String contentType = extractAnnotationValue(anno.get(), "produces");

                // For @RequestMapping, check the method attribute
                if ("RequestMapping".equals(annoName)) {
                    String methodAttr = extractAnnotationValue(anno.get(), "method");
                    if (methodAttr != null) {
                        httpMethod = methodAttr.toUpperCase().replace("REQUESTMETHOD.", "");
                    }
                }

                return new MappingInfo(httpMethod, path, contentType);
            }
        }
        return null;
    }

    private String extractMappingPath(AnnotationExpr anno) {
        // Try value() or path()
        String path = extractAnnotationValue(anno, "value");
        if (path == null) path = extractAnnotationValue(anno, "path");
        if (path == null) {
            // Check if there's a single string literal argument
            if (anno instanceof SingleMemberAnnotationExpr single) {
                if (single.getMemberValue() instanceof StringLiteralExpr s) {
                    path = s.getValue();
                }
            }
        }
        return path != null ? path : "";
    }

    private String getClassBasePath(ClassOrInterfaceDeclaration clazz) {
        Optional<AnnotationExpr> anno = clazz.getAnnotationByName("RequestMapping");
        if (anno.isPresent()) {
            return extractMappingPath(anno.get());
        }
        return "";
    }

    // ---- Comment/Description Extraction ----

    /**
     * Extract description with priority: Javadoc > line comment > Swagger > null
     */
    private String extractDescription(NodeWithAnnotations<?> node) {
        // 1. Try Javadoc
        if (node instanceof NodeWithJavadoc<?> javadocNode) {
            Optional<JavadocComment> javadoc = javadocNode.getJavadocComment();
            if (javadoc.isPresent()) {
                return cleanJavadoc(javadoc.get().getContent());
            }
        }
        // 2. Try preceding line comments
        if (node instanceof BodyDeclaration<?> body) {
            for (Comment comment : body.getAllContainedComments()) {
                if (comment.isLineComment()) {
                    String text = comment.getContent().trim();
                    if (!text.startsWith("@")) {
                        return text;
                    }
                }
            }
        }

        // 3. Try Swagger annotations
        String swaggerDesc = extractSwaggerDescription(node);
        if (swaggerDesc != null) return swaggerDesc;

        return null;
    }

    private String extractMethodDescription(MethodDeclaration method) {
        // 1. Javadoc
        Optional<JavadocComment> javadoc = method.getJavadocComment();
        if (javadoc.isPresent()) {
            return cleanJavadoc(javadoc.get().getContent());
        }

        // 2. Line comment above the method
        for (Comment comment : method.getAllContainedComments()) {
            if (comment.isLineComment()) {
                String text = comment.getContent().trim();
                if (!text.startsWith("@")) return text;
            }
        }

        // Also check comments on the method itself
        method.getComment().ifPresent(c -> {
            // already checked above via getAllContainedComments
        });

        // 3. Swagger
        String swagger = extractSwaggerDescription(method);
        if (swagger != null) return swagger;

        // 4. Fallback to method name
        return method.getNameAsString();
    }

    private String extractMethodName(MethodDeclaration method, String description) {
        // Use @ApiOperation value if available
        String apiOp = extractAnnotationValue(method, "ApiOperation", "value");
        if (apiOp != null) return apiOp;

        String tag = extractAnnotationValue(method, "Operation", "summary");
        if (tag != null) return tag;

        // Use first line of description if it's short enough
        if (description != null && description.length() <= 50 && !description.contains("\n")) {
            return description;
        }

        // Fallback to method name
        return method.getNameAsString();
    }

    private String extractParamDescription(com.github.javaparser.ast.body.Parameter param) {
        // Check Javadoc @param tags on the parent method
        if (param.getParentNode().isPresent() &&
                param.getParentNode().get() instanceof MethodDeclaration method) {
            Optional<JavadocComment> javadoc = method.getJavadocComment();
            if (javadoc.isPresent()) {
                String paramName = param.getNameAsString();
                String docContent = javadoc.get().getContent();
                // Parse @param tags
                for (String line : docContent.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("@param")) {
                        String[] parts = trimmed.substring(6).trim().split("\\s+", 2);
                        if (parts.length >= 2 && parts[0].equals(paramName)) {
                            return parts[1].trim();
                        }
                    }
                }
            }
        }
        return null;
    }

    private String extractInlineComment(BodyDeclaration<?> node) {
        for (Comment comment : node.getAllContainedComments()) {
            if (comment.isLineComment()) {
                String text = comment.getContent().trim();
                if (!text.isEmpty()) return text;
            }
        }
        return null;
    }

    private String extractEnumValueDescription(EnumConstantDeclaration entry) {
        Optional<JavadocComment> javadoc = entry.getJavadocComment();
        if (javadoc.isPresent()) {
            return cleanJavadoc(javadoc.get().getContent());
        }
        String swaggerDesc = extractSwaggerDescription(entry);
        if (swaggerDesc != null) return swaggerDesc;
        return entry.getComment()
                .map(Comment::getContent)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .orElse(null);
    }

    private String extractRecordComponentDescription(RecordDeclaration record, String componentName) {
        Optional<JavadocComment> javadoc = record.getJavadocComment();
        if (javadoc.isEmpty()) return null;

        for (String line : javadoc.get().getContent().split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*")) trimmed = trimmed.substring(1).trim();
            if (!trimmed.startsWith("@param")) continue;
            String[] parts = trimmed.substring(6).trim().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equals(componentName)) {
                return parts[1].trim();
            }
        }
        return null;
    }

    // ---- Swagger Annotation Helpers ----

    private String extractSwaggerDescription(NodeWithAnnotations<?> node) {
        String desc = extractAnnotationValue(node, "ApiOperation", "value");
        if (desc != null) return desc;
        desc = extractAnnotationValue(node, "Operation", "summary");
        if (desc != null) return desc;
        desc = extractAnnotationValue(node, "Operation", "description");
        if (desc != null) return desc;
        desc = extractAnnotationValue(node, "Schema", "description");
        if (desc != null) return desc;
        desc = extractAnnotationValue(node, "ApiModel", "description");
        if (desc != null) return desc;
        desc = extractAnnotationValue(node, "ApiModelProperty", "value");
        if (desc != null) return desc;
        return extractAnnotationValue(node, "Schema", "title");
    }

    private String extractAnnotationValue(NodeWithAnnotations<?> node, String annoName, String attrName) {
        Optional<AnnotationExpr> anno = node.getAnnotationByName(annoName);
        if (anno.isEmpty()) return null;
        return extractAnnotationValue(anno.get(), attrName);
    }

    private String extractAnnotationValue(AnnotationExpr anno, String attrName) {
        if (anno instanceof SingleMemberAnnotationExpr single) {
            if ("value".equals(attrName) || attrName == null) {
                return extractStringValue(single.getMemberValue());
            }
            return null;
        }
        if (anno instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    return extractStringValue(pair.getValue());
                }
            }
        }
        return null;
    }

    private String extractStringValue(Expression expr) {
        if (expr instanceof StringLiteralExpr s) return s.getValue();
        if (expr instanceof FieldAccessExpr f) return f.toString();
        if (expr instanceof NameExpr n) return n.getNameAsString();
        return expr.toString();
    }

    private Optional<Boolean> extractAnnotationBoolean(NodeWithAnnotations<?> node, String annoName, String attrName) {
        Optional<AnnotationExpr> anno = node.getAnnotationByName(annoName);
        if (anno.isEmpty()) return Optional.empty();
        if (anno.get() instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    if (pair.getValue() instanceof BooleanLiteralExpr b) {
                        return Optional.of(b.getValue());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String annoName) {
        return node.getAnnotationByName(annoName).isPresent();
    }

    private List<String> extractTags(ClassOrInterfaceDeclaration clazz) {
        List<String> tags = new ArrayList<>();
        // @Api(tags = {...})
        Optional<AnnotationExpr> apiAnno = clazz.getAnnotationByName("Api");
        if (apiAnno.isPresent()) {
            extractStringArray(apiAnno.get(), "tags").ifPresent(tags::addAll);
        }
        // @Tag(name = "...")
        clazz.getAnnotationByName("Tag").ifPresent(anno -> {
            String name = extractAnnotationValue(anno, "name");
            if (name != null) tags.add(name);
        });
        return tags;
    }

    private List<String> extractMethodTags(MethodDeclaration method) {
        List<String> tags = new ArrayList<>();
        method.getAnnotationByName("Tag").ifPresent(anno -> {
            String name = extractAnnotationValue(anno, "name");
            if (name != null) tags.add(name);
        });
        return tags;
    }

    private Optional<List<String>> extractStringArray(AnnotationExpr anno, String attrName) {
        if (anno instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    if (pair.getValue() instanceof ArrayInitializerExpr arr) {
                        List<String> values = arr.getValues().stream()
                                .map(this::extractStringValue)
                                .toList();
                        return Optional.of(values);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ---- Type Helpers ----

    private boolean isSimpleType(String type) {
        return PRIMITIVE_TYPES.contains(type) || type.startsWith("java.lang.");
    }

    private boolean isServletType(String type) {
        return type.contains("HttpServletRequest") || type.contains("HttpServletResponse") ||
                type.contains("ServletRequest") || type.contains("ServletResponse") ||
                type.contains("MultipartFile");
    }

    private String unboxGenericType(String type) {
        // ResponseEntity<T> -> T
        if (type.startsWith("ResponseEntity<")) {
            return SignatureUtil.extractInnerType(type);
        }
        return type;
    }

    private String resolveFullType(Type type, CompilationUnit cu) {
        if (type instanceof ClassOrInterfaceType cit) {
            String name = cit.getNameAsString();
            // Resolve outer type from imports
            String resolvedName = name;
            if (cu != null) {
                for (var imp : cu.getImports()) {
                    if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + name)) {
                        resolvedName = imp.getNameAsString();
                        break;
                    }
                }
            }
            // Recursively resolve generic type arguments
            if (cit.getTypeArguments().isPresent()) {
                List<String> resolvedArgs = new ArrayList<>();
                for (Type arg : cit.getTypeArguments().get()) {
                    resolvedArgs.add(resolveFullType(arg, cu));
                }
                String result = resolvedName + "<" + String.join(", ", resolvedArgs) + ">";
                log.info("resolveFullType: {} -> {} (cu={})", type.asString(), result, cu != null);
                return result;
            }
            log.info("resolveFullType (no generics): {} -> {} (cu={})", type.asString(), resolvedName, cu != null);
            return resolvedName;
        }
        log.info("resolveFullType (not ClassOrInterfaceType): {} -> {}", type.getClass().getSimpleName(), type.asString());
        return type.asString();
    }

    private String getFullyQualifiedClassName(BodyDeclaration<?> clazz, CompilationUnit cu) {
        String simpleName = "";
        if (clazz instanceof ClassOrInterfaceDeclaration c) simpleName = c.getNameAsString();
        else if (clazz instanceof EnumDeclaration e) simpleName = e.getNameAsString();
        else if (clazz instanceof RecordDeclaration r) simpleName = r.getNameAsString();

        if (cu.getPackageDeclaration().isPresent()) {
            return cu.getPackageDeclaration().get().getNameAsString() + "." + simpleName;
        }
        return simpleName;
    }

    private String cleanJavadoc(String content) {
        if (content == null) return null;
        return Arrays.stream(content.split("\n"))
                .map(line -> {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*")) trimmed = trimmed.substring(1).trim();
                    if (trimmed.startsWith("@")) return ""; // skip tags
                    return trimmed;
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private String combinePaths(String base, String method) {
        if (base == null) base = "";
        if (method == null) method = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!method.startsWith("/") && !method.isEmpty()) method = "/" + method;
        if (method.equals("/")) method = "";
        return base + method;
    }
}
