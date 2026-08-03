package ink.icoding.marginalia.core.service;

import com.github.javaparser.ast.CompilationUnit;
import ink.icoding.marginalia.core.model.*;
import ink.icoding.marginalia.core.parser.SourceParser;
import ink.icoding.marginalia.core.persistence.PersistenceManager;
import ink.icoding.marginalia.core.scanner.SourceScanner;
import ink.icoding.marginalia.core.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main service that orchestrates scanning, parsing, merging, and persistence.
 */
public class MarginaliaService {

    private static final Logger log = LoggerFactory.getLogger(MarginaliaService.class);

    private final SourceScanner scanner;
    private final SourceParser parser;
    private final PersistenceManager persistence;
    private final String basePackage;
    private List<String> sourceDirs;
    private final Map<String, List<Path>> sourceTypeIndex = new LinkedHashMap<>();

    public MarginaliaService(String dataDir, String basePackage, List<String> sourceDirs) {
        this.scanner = new SourceScanner();
        this.parser = new SourceParser();
        this.persistence = new PersistenceManager(dataDir);
        this.basePackage = basePackage;
        this.sourceDirs = sourceDirs != null ? sourceDirs : List.of();
    }

    /**
     * Scan all controllers, merge with persisted data, and save.
     * This is the main entry point called at application startup.
     */
    public ScanResult scanAndMerge() {
        log.info("Starting Marginalia scan...");
        long startTime = System.currentTimeMillis();

        List<String> effectiveSourceDirs = sourceDirs;
        if (effectiveSourceDirs.isEmpty()) {
            effectiveSourceDirs = scanner.detectSourceDirs(System.getProperty("user.dir"));
            this.sourceDirs = effectiveSourceDirs; // 回写到实例字段，供实体搜索使用
            log.info("Auto-detected source dirs: {}", effectiveSourceDirs);
        }

        List<Path> sourceFiles = scanner.findSourceFiles(effectiveSourceDirs);
        log.info("Found {} Java source files", sourceFiles.size());
        buildSourceTypeIndex(effectiveSourceDirs, sourceFiles);

        List<ControllerDoc> generatedControllers = new ArrayList<>();
        Map<String, EntityDoc> generatedEntities = new LinkedHashMap<>();

        // Parse all source files
        for (Path file : sourceFiles) {
            try {
                List<ControllerDoc> controllers = parser.parseSourceFile(file, basePackage);
                generatedControllers.addAll(controllers);

                // Extract entity types from request/response bodies
                for (ControllerDoc ctrl : controllers) {
                    for (ApiDoc api : ctrl.getApis()) {
                        log.info("API: {} {} -> responseType={}, responseFullType={}, reqBodyType={}, reqBodyFullType={}",
                                api.getHttpMethod(), api.getPath(),
                                api.getResponse() != null ? api.getResponse().getType() : "null",
                                api.getResponse() != null ? api.getResponse().getFullType() : "null",
                                api.getRequestBody() != null ? api.getRequestBody().getType() : "null",
                                api.getRequestBody() != null ? api.getRequestBody().getFullType() : "null");
                        try {
                            extractEntities(api, file, generatedEntities);
                        } catch (Exception e) {
                            log.warn("Failed to extract entities for {} {} in {}: {}",
                                    api.getHttpMethod(), api.getPath(), file, e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Skipping file (parse error): {}", file, e);
            }
        }

        // Merge controllers with persisted data
        int newApis = 0, updatedApis = 0, unchangedApis = 0;
        for (ControllerDoc genCtrl : generatedControllers) {
            // Ensure index entry exists
            persistence.getOrCreateIndexEntry(genCtrl.getClassName(), genCtrl.getName());

            Optional<ControllerDoc> persistedCtrl = persistence.getControllerByClass(genCtrl.getClassName());

            if (persistedCtrl.isPresent()) {
                // Merge APIs
                Map<String, ApiDoc> persistedApis = persistedCtrl.get().getApis().stream()
                        .collect(Collectors.toMap(ApiDoc::getMethodSignature, a -> a, (a, b) -> b));

                List<ApiDoc> mergedApis = new ArrayList<>();
                for (ApiDoc genApi : genCtrl.getApis()) {
                    ApiDoc persistedApi = persistedApis.get(genApi.getMethodSignature());
                    ApiDoc merged = persistence.mergeApi(genApi, persistedApi);
                    mergedApis.add(merged);

                    if (persistedApi == null) newApis++;
                    else if (merged.getPersistMeta() != null && merged.getPersistMeta().isUserModified()) updatedApis++;
                    else unchangedApis++;
                }

                genCtrl.setApis(mergedApis);
            } else {
                newApis += genCtrl.getApis().size();
            }

            persistence.saveController(genCtrl);
        }

        // Merge entities with persisted data
        log.info("Discovered {} entities: {}", generatedEntities.size(), generatedEntities.keySet());
        for (Map.Entry<String, EntityDoc> entry : generatedEntities.entrySet()) {
            Optional<EntityDoc> persistedEntity = persistence.getEntityByName(entry.getKey());
            EntityDoc merged;
            if (persistedEntity.isPresent()) {
                merged = persistence.mergeEntity(entry.getValue(), persistedEntity.get());
            } else {
                merged = entry.getValue();
            }
            persistence.saveEntity(merged);
            log.info("Saved entity: {}", merged.getFullName());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Marginalia scan complete: {} controllers, {} new APIs, {} updated, {} unchanged ({}ms)",
                generatedControllers.size(), newApis, updatedApis, unchangedApis, elapsed);

        return new ScanResult(generatedControllers.size(), newApis, updatedApis, unchangedApis, elapsed);
    }

    /**
     * Extract entity types from API request/response bodies and parse them.
     * Uses the controller's source file imports for type resolution.
     */
    private void extractEntities(ApiDoc api, Path sourceFile, Map<String, EntityDoc> entities) {
        // Parse the controller file to get its CompilationUnit (for import resolution)
        CompilationUnit cu = parser.parseFile(sourceFile);

        expandModelAttributeParams(api, sourceFile, cu, entities);

        // Extract from request body
        if (api.getRequestBody() != null && api.getRequestBody().getFullType() != null) {
            String type = api.getRequestBody().getFullType();
            discoverEntity(type, sourceFile, cu, entities, 0);
            // Set entity ref
            String simpleType = extractInnermostSimpleType(type);
            EntityDoc found = findCachedEntity(simpleType, entities);
            if (found != null) {
                api.getRequestBody().setEntityRef(found.getFullName());
            }
        }

        // Extract from response body
        if (api.getResponse() != null && api.getResponse().getFullType() != null) {
            String type = api.getResponse().getFullType();
            log.info("extractEntities [{}]: response fullType='{}', classes={}", api.getPath(), type, extractClassNames(type));
            discoverEntity(type, sourceFile, cu, entities, 0);
            // Set entity ref
            String simpleType = extractInnermostSimpleType(type);
            EntityDoc found = findCachedEntity(simpleType, entities);
            if (found != null) {
                api.getResponse().setEntityRef(found.getFullName());
            }
        } else if (api.getResponse() != null) {
            log.info("extractEntities [{}]: response fullType is NULL, type='{}'", api.getPath(), api.getResponse().getType());
        }
    }

    private void expandModelAttributeParams(ApiDoc api, Path sourceFile, CompilationUnit cu,
                                            Map<String, EntityDoc> entities) {
        if (api.getFormParams() == null || api.getFormParams().isEmpty()) return;

        List<EndpointParam> expandedFormParams = new ArrayList<>();
        for (EndpointParam modelParam : api.getFormParams()) {
            String type = modelParam.getFullType() != null ? modelParam.getFullType() : modelParam.getType();
            discoverEntity(type, sourceFile, cu, entities, 0);

            EntityDoc entity = resolveCachedFieldEntity(type, entities);
            if (entity == null || entity.isEnum() || entity.getFields() == null || entity.getFields().isEmpty()) {
                expandedFormParams.add(modelParam);
                continue;
            }

            for (FieldDoc field : entity.getFields()) {
                expandedFormParams.add(EndpointParam.builder()
                        .name(field.getName())
                        .type(field.getType())
                        .fullType(field.getFullType())
                        .description(field.getDescription())
                        .required(false)
                        .example(field.getExample())
                        .build());
            }
        }

        api.setFormParams(expandedFormParams);
    }

    private EntityDoc findCachedEntity(String simpleName, Map<String, EntityDoc> entities) {
        for (EntityDoc e : entities.values()) {
            if (e.getName().equals(simpleName)) return e;
        }
        return null;
    }

    /**
     * Discover and parse an entity type. Handles generic types like List<User>, Page<User>, etc.
     */
    private void discoverEntity(String type, Path sourceFile, CompilationUnit cu,
                                  Map<String, EntityDoc> entities, int depth) {
        if (depth > 5) return; // Prevent infinite recursion

        // Keep fully qualified names when resolveFullType has already done the hard work.
        // This avoids losing source location information for wildcard imports or cross-package entities.
        for (String fullName : extractQualifiedClassNames(type)) {
            String simpleName = SignatureUtil.simpleClassName(fullName);
            if (isSimpleType(simpleName) || entities.containsKey(fullName)) continue;
            EntityDoc entity = tryFindEntityByFqn(fullName, sourceFile);
            if (entity != null) {
                registerDiscoveredEntity(entity, type, sourceFile, cu, entities, depth);
            }
        }

        // Extract all class names from the type (handles generics)
        List<String> classNames = extractClassNames(type);
        log.debug("discoverEntity: type='{}', extracted classes={}, sourceDirs={}", type, classNames, sourceDirs);

        for (String className : classNames) {
            if (isSimpleType(className) || containsEntity(className, entities)) {
                log.debug("discoverEntity: skipping '{}' (simpleType={}, alreadyCached={})", className, isSimpleType(className), containsEntity(className, entities));
                continue;
            }

            EntityDoc entity = tryFindEntity(className, sourceFile, cu);
            if (entity != null) {
                registerDiscoveredEntity(entity, type, sourceFile, cu, entities, depth);
            } else {
                log.debug("discoverEntity: entity '{}' NOT found", className);
            }
        }
    }

    private void registerDiscoveredEntity(EntityDoc entity, String sourceType, Path sourceFile,
                                            CompilationUnit cu, Map<String, EntityDoc> entities, int depth) {
        if (entities.containsKey(entity.getFullName())) return;

        log.info("Discovered entity: {} with {} fields (from type '{}')",
                entity.getFullName(), entity.getFields() != null ? entity.getFields().size() : 0, sourceType);
        entities.put(entity.getFullName(), entity);

        if (entity.getFields() == null) return;
        for (FieldDoc field : entity.getFields()) {
            String fieldType = field.getFullType() != null ? field.getFullType() : field.getType();
            if (fieldType == null) continue;
            discoverEntity(fieldType, sourceFile, cu, entities, depth + 1);
            EntityDoc fieldEntity = resolveCachedFieldEntity(fieldType, entities);
            if (fieldEntity != null) {
                field.setEntityRef(fieldEntity.getFullName());
            }
        }
    }

    private boolean containsEntity(String simpleName, Map<String, EntityDoc> entities) {
        return entities.values().stream().anyMatch(e -> e.getName().equals(simpleName));
    }

    private EntityDoc resolveCachedFieldEntity(String type, Map<String, EntityDoc> entities) {
        for (String fullName : extractQualifiedClassNames(type)) {
            EntityDoc entity = entities.get(fullName);
            if (entity != null) return entity;
        }
        List<String> classNames = extractClassNames(type);
        for (int i = classNames.size() - 1; i >= 0; i--) {
            EntityDoc entity = findCachedEntity(classNames.get(i), entities);
            if (entity != null) return entity;
        }
        return null;
    }

    private List<String> extractQualifiedClassNames(String type) {
        List<String> names = new ArrayList<>();
        for (String token : splitTypeTokens(type)) {
            String simpleName = SignatureUtil.simpleClassName(token);
            if (token.contains(".") && !simpleName.isEmpty() &&
                    Character.isUpperCase(simpleName.charAt(0)) && !isSimpleType(simpleName)) {
                names.add(token);
            }
        }
        return names;
    }

    /**
     * Extract all class names from a type string, including generic parameters.
     * "ResponseEntity<List<User>>" -> ["ResponseEntity", "List", "User"]
     * "User" -> ["User"]
     */
    private List<String> extractClassNames(String type) {
        List<String> names = new ArrayList<>();
        for (String name : splitTypeTokens(type)) {
            // Get simple class name (handle fully qualified names like com.hbdx.entity.vo.InnerKnowledgeFileVO)
            String simpleName = SignatureUtil.simpleClassName(name);
            if (!simpleName.isEmpty() && !isSimpleType(simpleName) && Character.isUpperCase(simpleName.charAt(0))) {
                names.add(simpleName);
            }
        }
        return names;
    }

    private List<String> splitTypeTokens(String type) {
        if (type == null || type.isEmpty()) return List.of();
        String cleaned = type.replace(">", "").replace("<", ",").replace(" ", "")
                .replace("?extends", "").replace("?super", "").replace("[]", "");
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /**
     * Extract the innermost simple type from a generic type.
     * "List<User>" -> "User", "ResponseEntity<User>" -> "User", "User" -> "User"
     */
    private String extractInnermostSimpleType(String type) {
        if (type == null) return null;
        // Get everything between the last < and >
        int start = type.lastIndexOf('<');
        int end = type.lastIndexOf('>');
        if (start >= 0 && end > start) {
            String inner = type.substring(start + 1, end).trim();
            return SignatureUtil.simpleClassName(inner);
        }
        return SignatureUtil.simpleClassName(type);
    }

    /**
     * Try to find and parse an entity class by its simple name.
     * Strategy: 1) imports resolution 2) same package 3) sibling packages 4) source root search
     */
    private EntityDoc tryFindEntity(String simpleName, Path sourceFile, CompilationUnit cu) {
        // Strategy 1: Resolve from imports
        if (cu != null) {
            String fqn = parser.resolveImport(cu, simpleName);
            if (fqn != null) {
                log.debug("tryFindEntity '{}': resolved import to '{}'", simpleName, fqn);
                EntityDoc entity = tryFindEntityByFqn(fqn, sourceFile);
                if (entity != null) return entity;
            }
        }

        EntityDoc indexed = tryFindIndexedEntity(simpleName, simpleName);
        if (indexed != null) return indexed;

        // Strategy 2: Same package as controller
        Path parent = sourceFile.getParent();
        Path candidate = parent.resolve(simpleName + ".java");
        if (Files.exists(candidate)) {
            log.debug("tryFindEntity '{}': found in same package: {}", simpleName, candidate);
            EntityDoc entity = parser.parseEntity(candidate, simpleName);
            if (entity != null) return entity;
        }

        // Strategy 3: Common sibling packages (model, entity, dto, vo, domain)
        for (String subDir : List.of("model", "entity", "dto", "vo", "domain", "bean", "pojo")) {
            Path sub = parent.resolve(subDir).resolve(simpleName + ".java");
            if (Files.exists(sub)) {
                log.debug("tryFindEntity '{}': found in sub-package: {}", simpleName, sub);
                EntityDoc entity = parser.parseEntity(sub, simpleName);
                if (entity != null) return entity;
            }
        }

        // Strategy 4: Sibling packages at parent level (e.g., controller is in .../controller, entity in .../model)
        Path grandParent = parent.getParent();
        if (grandParent != null) {
            for (String subDir : List.of("model", "entity", "dto", "vo", "domain", "bean", "pojo")) {
                Path sibling = grandParent.resolve(subDir).resolve(simpleName + ".java");
                if (Files.exists(sibling)) {
                    log.debug("tryFindEntity '{}': found in sibling package: {}", simpleName, sibling);
                    EntityDoc entity = parser.parseEntity(sibling, simpleName);
                    if (entity != null) return entity;
                }
            }
        }

        // Strategy 5: Full source directory search
        log.debug("tryFindEntity '{}': trying full search, sourceDirs={}", simpleName, sourceDirs);
        for (String srcDir : sourceDirs) {
            try {
                Path srcPath = Path.of(srcDir);
                Path found = findFile(srcPath, simpleName + ".java");
                if (found != null) {
                    log.debug("tryFindEntity '{}': found via full search: {}", simpleName, found);
                    EntityDoc entity = parser.parseEntity(found, simpleName);
                    if (entity != null) return entity;
                }
            } catch (Exception e) {
                log.debug("tryFindEntity '{}': error in full search for dir {}: {}", simpleName, srcDir, e.getMessage());
            }
        }

        log.debug("tryFindEntity '{}': NOT FOUND after all strategies", simpleName);
        return null;
    }

    /**
     * Try to find and parse an entity by its fully qualified name.
     * Converts package to path and looks for the file.
     */
    private EntityDoc tryFindEntityByFqn(String fqn, Path sourceFile) {
        EntityDoc indexed = tryFindIndexedEntity(fqn, SignatureUtil.simpleClassName(fqn));
        if (indexed != null) return indexed;

        // Convert fully qualified name to relative path
        String relativePath = fqn.replace('.', '/') + ".java";

        // Try from each source directory
        for (String srcDir : sourceDirs) {
            Path candidate = Path.of(srcDir, relativePath);
            if (Files.exists(candidate)) {
                return parser.parseEntity(candidate, SignatureUtil.simpleClassName(fqn));
            }
        }

        // Try relative to the source file's source root
        for (String srcDir : sourceDirs) {
            Path srcPath = Path.of(srcDir);
            if (sourceFile.startsWith(srcPath)) {
                Path candidate = srcPath.resolve(relativePath);
                if (Files.exists(candidate)) {
                    return parser.parseEntity(candidate, SignatureUtil.simpleClassName(fqn));
                }
            }
        }

        return null;
    }

    private void buildSourceTypeIndex(List<String> effectiveSourceDirs, List<Path> sourceFiles) {
        sourceTypeIndex.clear();
        List<Path> roots = effectiveSourceDirs.stream()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();

        for (Path sourceFile : sourceFiles) {
            Path absoluteFile = sourceFile.toAbsolutePath().normalize();
            String fileName = sourceFile.getFileName().toString();
            if (!fileName.endsWith(".java")) continue;
            String simpleName = fileName.substring(0, fileName.length() - 5);
            indexSourceType(simpleName, sourceFile);

            for (Path root : roots) {
                if (!absoluteFile.startsWith(root)) continue;
                String relativeName = root.relativize(absoluteFile).toString()
                        .replace('\\', '.').replace('/', '.');
                String fullName = relativeName.substring(0, relativeName.length() - 5);
                indexSourceType(fullName, sourceFile);
                break;
            }
        }
        log.debug("Built source type index with {} keys", sourceTypeIndex.size());
    }

    private void indexSourceType(String typeName, Path sourceFile) {
        sourceTypeIndex.computeIfAbsent(typeName, ignored -> new ArrayList<>()).add(sourceFile);
    }

    private EntityDoc tryFindIndexedEntity(String typeName, String expectedSimpleName) {
        List<Path> candidates = sourceTypeIndex.get(typeName);
        if (candidates == null) return null;
        for (Path candidate : candidates) {
            EntityDoc entity = parser.parseEntity(candidate, expectedSimpleName);
            if (entity != null) {
                log.debug("Found entity '{}' through source index: {}", typeName, candidate);
                return entity;
            }
        }
        return null;
    }

    private Path findFile(Path dir, String fileName) {
        try (var stream = Files.walk(dir, 10)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Public API for Web Controller ====================

    public List<ControllerDoc> getAllControllers() {
        return persistence.getAllControllers();
    }

    public Optional<ControllerDoc> getController(String className) {
        return persistence.getControllerByClass(className);
    }

    public Optional<ApiDoc> getApi(String signature) {
        return persistence.getApiBySignature(signature);
    }

    public List<EntityDoc> getAllEntities() {
        return persistence.getAllEntities();
    }

    public Optional<EntityDoc> getEntity(String fullName) {
        return persistence.getEntityByName(fullName);
    }

    public List<IndexEntry> getIndexEntries() {
        return persistence.getAllIndexEntries();
    }

    /**
     * Update an API doc (user modification).
     */
    public void updateApi(ApiDoc api) {
        if (api.getPersistMeta() == null) {
            api.setPersistMeta(PersistMeta.builder()
                    .userModified(true)
                    .modifiedFields(new HashSet<>())
                    .lastModified(java.time.LocalDateTime.now())
                    .version(1)
                    .build());
        }
        api.getPersistMeta().setUserModified(true);
        api.getPersistMeta().setLastModified(java.time.LocalDateTime.now());

        String controllerDir = SignatureUtil.sanitizeFileName(api.getControllerName());
        persistence.saveApi(controllerDir, api);
    }

    /**
     * Update an entity doc (user modification).
     */
    public void updateEntity(EntityDoc entity) {
        if (entity.getPersistMeta() == null) {
            entity.setPersistMeta(PersistMeta.builder()
                    .userModified(true)
                    .modifiedFields(new HashSet<>())
                    .lastModified(java.time.LocalDateTime.now())
                    .version(1)
                    .build());
        }
        entity.getPersistMeta().setUserModified(true);
        entity.getPersistMeta().setLastModified(java.time.LocalDateTime.now());
        persistence.saveEntity(entity);
    }

    /**
     * Update an index entry (user category/grouping changes).
     */
    public void updateIndexEntry(IndexEntry entry) {
        persistence.updateIndexEntry(entry);
    }

    /**
     * Force re-scan: clear persisted data and regenerate.
     */
    public ScanResult forceRescan() {
        // This will regenerate everything, merging with existing persisted data
        return scanAndMerge();
    }

    /**
     * Build the documentation tree structure.
     */
    public List<DocTree> buildDocTree() {
        List<IndexEntry> entries = persistence.getAllIndexEntries();
        List<ControllerDoc> controllers = persistence.getAllControllers();

        // Group by category
        Map<String, List<ControllerDoc>> categorized = new LinkedHashMap<>();
        Map<String, IndexEntry> entryMap = entries.stream()
                .collect(Collectors.toMap(IndexEntry::getControllerClass, e -> e));

        for (ControllerDoc ctrl : controllers) {
            IndexEntry entry = entryMap.get(ctrl.getClassName());
            String category = (entry != null && entry.getCategory() != null && !entry.getCategory().isEmpty())
                    ? entry.getCategory() : "Default";
            categorized.computeIfAbsent(category, k -> new ArrayList<>()).add(ctrl);
        }

        // Build tree
        List<DocTree> roots = new ArrayList<>();
        for (Map.Entry<String, List<ControllerDoc>> catEntry : categorized.entrySet()) {
            DocTree catNode = DocTree.builder()
                    .name(catEntry.getKey())
                    .type("directory")
                    .icon("folder")
                    .build();

            for (ControllerDoc ctrl : catEntry.getValue()) {
                DocTree ctrlNode = DocTree.builder()
                        .name(ctrl.getName())
                        .type("controller")
                        .id(ctrl.getClassName())
                        .icon("controller")
                        .build();

                for (ApiDoc api : ctrl.getApis()) {
                    DocTree apiNode = DocTree.builder()
                            .name(api.getName())
                            .type("api")
                            .id(api.getMethodSignature())
                            .icon(api.getHttpMethod().toLowerCase())
                            .build();
                    ctrlNode.getChildren().add(apiNode);
                }

                catNode.getChildren().add(ctrlNode);
            }

            roots.add(catNode);
        }

        return roots;
    }

    public PersistenceManager getPersistence() {
        return persistence;
    }

    public record ScanResult(int controllers, int newApis, int updatedApis, int unchangedApis, long elapsedMs) {}

    private boolean isSimpleType(String type) {
        if (type == null || type.isEmpty()) return true;
        // Primitives and wrappers
        if (type.matches("(void|Void|String|Object|int|long|double|float|boolean|byte|short|char|Integer|Long|Double|Float|Boolean|Byte|Short|Character|BigDecimal|BigInteger|UUID|Date|Instant|LocalDate|LocalDateTime|OffsetDateTime|ZonedDateTime|Duration)")) return true;
        // Collection types (with or without generics)
        if (type.matches("(List|Map|Set|Collection|Queue|Deque|ArrayList|LinkedList|HashMap|TreeMap|HashSet|TreeSet|LinkedHashMap|LinkedHashSet|Page|Pageable|Slice|Stream|Iterator|Iterable)(\\b|<).*")) return true;
        // Common wrapper/response types
        if (type.matches("(ResponseEntity|ModelAndView|RedirectView|HttpEntity|Mono|Flux|CompletableFuture|Future|DeferredResult|Callable|R|Result|ApiResult|ApiResponse|BaseResponse|ResponseEntity)(\\b|<).*")) return true;
        // java.lang types
        if (type.matches("(Class|Number|Comparable|Serializable|Cloneable|AutoCloseable)(\\b|<).*")) return true;
        return false;
    }
}
