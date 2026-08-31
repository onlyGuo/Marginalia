package ink.icoding.marginalia.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ink.icoding.marginalia.core.model.*;
import ink.icoding.marginalia.core.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages persistence of API documentation using a directory-based structure.
 *
 * Structure:
 * {dataDir}/
 *   index.json           - Controller index mapping
 *   controllers/
 *     {ControllerName}/
 *       _meta.json        - Controller metadata
 *       {api-id}.json     - Individual API endpoint files
 *   entities/
 *     {EntityName}.json   - Entity definitions
 */
public class PersistenceManager {

    private static final Logger log = LoggerFactory.getLogger(PersistenceManager.class);

    private final Path dataDir;
    private final Path controllersDir;
    private final Path entitiesDir;
    private final ObjectMapper mapper;

    // In-memory cache for index
    private final Map<String, IndexEntry> indexCache = new ConcurrentHashMap<>();
    // Cache for loaded controller docs
    private final Map<String, ControllerDoc> controllerCache = new ConcurrentHashMap<>();
    // Cache for loaded API docs
    private final Map<String, ApiDoc> apiCache = new ConcurrentHashMap<>();
    // Cache for loaded entity docs
    private final Map<String, EntityDoc> entityCache = new ConcurrentHashMap<>();

    public PersistenceManager(String dataDirPath) {
        this.dataDir = Paths.get(dataDirPath);
        this.controllersDir = dataDir.resolve("controllers");
        this.entitiesDir = dataDir.resolve("entities");

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        ensureDirectories();
        loadIndex();
        loadAllCached();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(controllersDir);
            Files.createDirectories(entitiesDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directories", e);
        }
    }

    // ==================== Index Management ====================

    /**
     * Load the index.json file into memory.
     */
    private void loadIndex() {
        Path indexPath = dataDir.resolve("index.json");
        if (Files.exists(indexPath)) {
            try {
                String content = Files.readString(indexPath);
                List<IndexEntry> entries = mapper.readValue(content, new TypeReference<>() {});
                for (IndexEntry entry : entries) {
                    indexCache.put(entry.getControllerClass(), entry);
                }
                log.info("Loaded {} index entries", entries.size());
            } catch (IOException e) {
                log.error("Failed to load index.json", e);
            }
        }
    }

    /**
     * Save the index to disk.
     */
    public void saveIndex() {
        Path indexPath = dataDir.resolve("index.json");
        try {
            List<IndexEntry> entries = new ArrayList<>(indexCache.values());
            entries.sort(Comparator.comparingInt(IndexEntry::getOrder));
            mapper.writeValue(indexPath.toFile(), entries);
        } catch (IOException e) {
            log.error("Failed to save index.json", e);
        }
    }

    /**
     * Get or create an index entry for a controller.
     */
    public IndexEntry getOrCreateIndexEntry(String controllerClass, String displayName) {
        return indexCache.computeIfAbsent(controllerClass, k -> {
            IndexEntry entry = IndexEntry.builder()
                    .controllerClass(k)
                    .displayName(displayName)
                    .category("")
                    .order(indexCache.size())
                    .build();
            saveIndex();
            return entry;
        });
    }

    /**
     * Update an index entry (for user customizations like category/grouping).
     */
    public void updateIndexEntry(IndexEntry entry) {
        indexCache.put(entry.getControllerClass(), entry);
        saveIndex();
    }

    /**
     * Get all index entries.
     */
    public List<IndexEntry> getAllIndexEntries() {
        return new ArrayList<>(indexCache.values());
    }

    // ==================== Controller Persistence ====================

    /**
     * Save a controller document to its directory.
     */
    public void saveController(ControllerDoc doc) {
        String dirName = SignatureUtil.sanitizeFileName(doc.getName());
        Path ctrlDir = controllersDir.resolve(dirName);
        try {
            Files.createDirectories(ctrlDir);

            // Save metadata
            Path metaPath = ctrlDir.resolve("_meta.json");
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("className", doc.getClassName());
            meta.put("name", doc.getName());
            meta.put("basePath", doc.getBasePath());
            meta.put("description", doc.getDescription());
            meta.put("tags", doc.getTags());
            mapper.writeValue(metaPath.toFile(), meta);

            // Save individual API files
            for (ApiDoc api : doc.getApis()) {
                saveApi(dirName, api);
            }

            controllerCache.put(doc.getClassName(), doc);
            log.debug("Saved controller: {} with {} APIs", doc.getName(), doc.getApis().size());
        } catch (IOException e) {
            log.error("Failed to save controller: {}", doc.getName(), e);
        }
    }

    /**
     * Save an individual API doc to its file.
     */
    public void saveApi(String controllerDirName, ApiDoc api) {
        Path ctrlDir = controllersDir.resolve(SignatureUtil.sanitizeFileName(controllerDirName));
        try {
            Files.createDirectories(ctrlDir);
            String fileName = SignatureUtil.sanitizeFileName(api.getId()) + ".json";
            Path apiPath = ctrlDir.resolve(fileName);

            // Update persist meta
            if (api.getPersistMeta() == null) {
                api.setPersistMeta(PersistMeta.builder()
                        .userModified(false)
                        .lastGenerated(LocalDateTime.now())
                        .version(1)
                        .modifiedFields(new HashSet<>())
                        .build());
            }
            api.getPersistMeta().setLastModified(LocalDateTime.now());

            mapper.writeValue(apiPath.toFile(), api);
            apiCache.put(api.getMethodSignature(), api);
            // Also update the API in controllerCache so GET /endpoints returns fresh data
            ControllerDoc ctrl = controllerCache.get(api.getControllerClass());
            if (ctrl != null) {
                List<ApiDoc> apis = ctrl.getApis();
                for (int i = 0; i < apis.size(); i++) {
                    if (apis.get(i).getMethodSignature().equals(api.getMethodSignature())) {
                        apis.set(i, api);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to save API: {}", api.getId(), e);
        }
    }

    /**
     * Save an entity document.
     */
    public void saveEntity(EntityDoc entity) {
        try {
            String fileName = SignatureUtil.sanitizeFileName(entity.getName()) + ".json";
            Path entityPath = entitiesDir.resolve(fileName);

            if (entity.getPersistMeta() == null) {
                entity.setPersistMeta(PersistMeta.builder()
                        .userModified(false)
                        .lastGenerated(LocalDateTime.now())
                        .version(1)
                        .modifiedFields(new HashSet<>())
                        .build());
            }
            entity.getPersistMeta().setLastModified(LocalDateTime.now());

            mapper.writeValue(entityPath.toFile(), entity);
            entityCache.put(entity.getFullName(), entity);
        } catch (IOException e) {
            log.error("Failed to save entity: {}", entity.getName(), e);
        }
    }

    // ==================== Loading ====================

    private void loadAllCached() {
        // Load all controllers
        if (Files.exists(controllersDir)) {
            try (Stream<Path> dirs = Files.list(controllersDir)) {
                dirs.filter(Files::isDirectory).forEach(this::loadControllerDir);
            } catch (IOException e) {
                log.error("Error loading controllers", e);
            }
        }

        // Load all entities
        if (Files.exists(entitiesDir)) {
            try (Stream<Path> files = Files.list(entitiesDir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadEntityFile);
            } catch (IOException e) {
                log.error("Error loading entities", e);
            }
        }
    }

    private void loadControllerDir(Path ctrlDir) {
        try {
            Path metaPath = ctrlDir.resolve("_meta.json");
            if (!Files.exists(metaPath)) return;

            Map<String, Object> meta = mapper.readValue(metaPath.toFile(), new TypeReference<>() {});
            String className = (String) meta.get("className");

            List<ApiDoc> apis = new ArrayList<>();
            try (Stream<Path> files = Files.list(ctrlDir)) {
                files.filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().equals("_meta.json"))
                        .forEach(apiPath -> {
                            try {
                                ApiDoc api = mapper.readValue(apiPath.toFile(), ApiDoc.class);
                                apis.add(api);
                                apiCache.put(api.getMethodSignature(), api);
                            } catch (IOException e) {
                                log.error("Error loading API file: {}", apiPath, e);
                            }
                        });
            }

            ControllerDoc doc = ControllerDoc.builder()
                    .className(className)
                    .name((String) meta.get("name"))
                    .basePath((String) meta.get("basePath"))
                    .description((String) meta.get("description"))
                    .tags(meta.containsKey("tags") ? (List<String>) meta.get("tags") : List.of())
                    .apis(apis)
                    .build();

            controllerCache.put(className, doc);
        } catch (Exception e) {
            log.error("Error loading controller dir: {}", ctrlDir, e);
        }
    }

    private void loadEntityFile(Path entityPath) {
        try {
            EntityDoc entity = mapper.readValue(entityPath.toFile(), EntityDoc.class);
            entityCache.put(entity.getFullName(), entity);
        } catch (IOException e) {
            log.error("Error loading entity file: {}", entityPath, e);
        }
    }

    // ==================== Getters ====================

    public Optional<ApiDoc> getApiBySignature(String signature) {
        return Optional.ofNullable(apiCache.get(signature));
    }

    public Optional<ControllerDoc> getControllerByClass(String className) {
        return Optional.ofNullable(controllerCache.get(className));
    }

    public Optional<EntityDoc> getEntityByName(String fullName) {
        return Optional.ofNullable(entityCache.get(fullName));
    }

    public List<ControllerDoc> getAllControllers() {
        return new ArrayList<>(controllerCache.values());
    }

    public List<EntityDoc> getAllEntities() {
        return new ArrayList<>(entityCache.values());
    }

    public List<ApiDoc> getAllApis() {
        return new ArrayList<>(apiCache.values());
    }

    // ==================== Merge Logic ====================

    /**
     * Merge generated API with persisted data.
     * Field-level merge: user-modified fields take priority, new generated fields are added.
     */
    public ApiDoc mergeApi(ApiDoc generated, ApiDoc persisted) {
        if (persisted == null) return generated;
        if (generated == null) return persisted;

        PersistMeta meta = persisted.getPersistMeta();
        if (meta == null) {
            meta = PersistMeta.builder().modifiedFields(new HashSet<>()).build();
        }

        Set<String> modifiedFields = meta.getModifiedFields() != null ?
                meta.getModifiedFields() : new HashSet<>();

        ApiDoc.ApiDocBuilder merged = ApiDoc.builder()
                .id(generated.getId())
                .methodSignature(generated.getMethodSignature())
                .controllerClass(generated.getControllerClass())
                .controllerName(generated.getControllerName())
                .methodName(generated.getMethodName());

        // For each field, use persisted if user-modified, otherwise use generated
        merged.httpMethod(modifiedFields.contains("httpMethod") ? persisted.getHttpMethod() : generated.getHttpMethod());
        merged.path(modifiedFields.contains("path") ? persisted.getPath() : generated.getPath());
        merged.name(modifiedFields.contains("name") ? persisted.getName() : generated.getName());
        merged.description(modifiedFields.contains("description") ? persisted.getDescription() : generated.getDescription());
        merged.contentType(modifiedFields.contains("contentType") ? persisted.getContentType() : generated.getContentType());
        merged.sse(generated.isSse()); // Always from generated
        merged.deprecated(modifiedFields.contains("deprecated") ? persisted.isDeprecated() : generated.isDeprecated());

        // Merge parameters
        merged.pathVariables(mergeParams(generated.getPathVariables(), persisted.getPathVariables(), modifiedFields, "pathVariables"));
        merged.queryParams(mergeParams(generated.getQueryParams(), persisted.getQueryParams(), modifiedFields, "queryParams"));
        merged.headerParams(mergeParams(generated.getHeaderParams(), persisted.getHeaderParams(), modifiedFields, "headerParams"));
        merged.cookieParams(mergeParams(generated.getCookieParams(), persisted.getCookieParams(), modifiedFields, "cookieParams"));
        merged.formParams(mergeParams(generated.getFormParams(), persisted.getFormParams(), modifiedFields, "formParams"));

        // Merge request body
        merged.requestBody(mergeRequestBody(generated.getRequestBody(), persisted.getRequestBody(), modifiedFields));

        // Merge response
        merged.response(mergeResponse(generated.getResponse(), persisted.getResponse(), modifiedFields));

        // Tags: merge both
        Set<String> allTags = new LinkedHashSet<>();
        if (persisted.getTags() != null) allTags.addAll(persisted.getTags());
        if (generated.getTags() != null) allTags.addAll(generated.getTags());
        merged.tags(new ArrayList<>(allTags));

        // Debug state: always from persisted (user edits)
        merged.debugMethod(persisted.getDebugMethod());
        merged.debugUrl(persisted.getDebugUrl());
        merged.debugHeaders(persisted.getDebugHeaders() != null ? persisted.getDebugHeaders() : new LinkedHashMap<>());
        merged.debugParams(persisted.getDebugParams() != null ? persisted.getDebugParams() : new LinkedHashMap<>());
        merged.debugHeaderDescs(persisted.getDebugHeaderDescs() != null ? persisted.getDebugHeaderDescs() : new LinkedHashMap<>());
        merged.debugParamDescs(persisted.getDebugParamDescs() != null ? persisted.getDebugParamDescs() : new LinkedHashMap<>());
        merged.debugBody(persisted.getDebugBody());
        merged.debugResponse(persisted.getDebugResponse());

        // Update meta
        meta.setLastGenerated(LocalDateTime.now());
        meta.setVersion(meta.getVersion() + 1);
        merged.persistMeta(meta);

        return merged.build();
    }

    private List<EndpointParam> mergeParams(List<EndpointParam> generated, List<EndpointParam> persisted,
                                              Set<String> modifiedFields, String fieldGroup) {
        if (persisted == null || persisted.isEmpty()) return generated;
        if (generated == null || generated.isEmpty()) return persisted;

        Map<String, EndpointParam> persistedMap = persisted.stream()
                .collect(Collectors.toMap(EndpointParam::getName, p -> p, (a, b) -> b));

        List<EndpointParam> result = new ArrayList<>();
        for (EndpointParam genParam : generated) {
            EndpointParam persistedParam = persistedMap.get(genParam.getName());
            if (persistedParam != null) {
                result.add(mergeEndpointParam(genParam, persistedParam, modifiedFields, fieldGroup + "." + genParam.getName()));
            } else {
                result.add(genParam);
            }
        }
        return result;
    }

    private EndpointParam mergeEndpointParam(EndpointParam generated, EndpointParam persisted,
                                               Set<String> modifiedFields, String fieldPrefix) {
        EndpointParam.EndpointParamBuilder builder = EndpointParam.builder()
                .name(generated.getName())
                .type(generated.getType())
                .fullType(generated.getFullType());

        builder.description(modifiedFields.contains(fieldPrefix + ".description") ?
                persisted.getDescription() : generated.getDescription());
        builder.required(modifiedFields.contains(fieldPrefix + ".required") ?
                persisted.isRequired() : generated.isRequired());
        builder.defaultValue(modifiedFields.contains(fieldPrefix + ".defaultValue") ?
                persisted.getDefaultValue() : generated.getDefaultValue());
        builder.example(modifiedFields.contains(fieldPrefix + ".example") ?
                persisted.getExample() : generated.getExample());

        return builder.build();
    }

    private RequestBodyDoc mergeRequestBody(RequestBodyDoc generated, RequestBodyDoc persisted,
                                              Set<String> modifiedFields) {
        if (persisted == null) return generated;
        if (generated == null) return persisted;

        RequestBodyDoc.RequestBodyDocBuilder builder = RequestBodyDoc.builder()
                .type(generated.getType())
                .fullType(generated.getFullType())
                .genericType(generated.getGenericType())
                .entityRef(generated.getEntityRef());

        builder.description(modifiedFields.contains("requestBody.description") ?
                persisted.getDescription() : generated.getDescription());
        builder.required(modifiedFields.contains("requestBody.required") ?
                persisted.isRequired() : generated.isRequired());
        builder.contentType(modifiedFields.contains("requestBody.contentType") ?
                persisted.getContentType() : generated.getContentType());
        builder.example(modifiedFields.contains("requestBody.example") ?
                persisted.getExample() : generated.getExample());

        return builder.build();
    }

    private ResponseDoc mergeResponse(ResponseDoc generated, ResponseDoc persisted,
                                        Set<String> modifiedFields) {
        if (persisted == null) return generated;
        if (generated == null) return persisted;

        ResponseDoc.ResponseDocBuilder builder = ResponseDoc.builder()
                .type(generated.getType())
                .fullType(generated.getFullType())
                .genericType(generated.getGenericType())
                .entityRef(generated.getEntityRef());

        builder.statusCode(modifiedFields.contains("response.statusCode") ?
                persisted.getStatusCode() : generated.getStatusCode());
        builder.description(modifiedFields.contains("response.description") ?
                persisted.getDescription() : generated.getDescription());
        builder.contentType(modifiedFields.contains("response.contentType") ?
                persisted.getContentType() : generated.getContentType());
        builder.example(modifiedFields.contains("response.example") ?
                persisted.getExample() : generated.getExample());

        return builder.build();
    }

    /**
     * Merge entity: field-level merge with user modifications taking priority.
     */
    public EntityDoc mergeEntity(EntityDoc generated, EntityDoc persisted) {
        if (persisted == null) return generated;
        if (generated == null) return persisted;

        PersistMeta meta = persisted.getPersistMeta();
        if (meta == null) {
            meta = PersistMeta.builder().modifiedFields(new HashSet<>()).build();
        }
        Set<String> modifiedFields = meta.getModifiedFields() != null ?
                meta.getModifiedFields() : new HashSet<>();

        EntityDoc.EntityDocBuilder merged = EntityDoc.builder()
                .name(generated.getName())
                .fullName(generated.getFullName())
                .typeParameters(generated.getTypeParameters())
                .isEnum(generated.isEnum())
                .enumValues(generated.getEnumValues())
                .enumValueDocs(generated.getEnumValueDocs())
                .superClass(generated.getSuperClass());

        merged.description(modifiedFields.contains("description") ?
                persisted.getDescription() : generated.getDescription());

        // Merge fields
        Map<String, FieldDoc> persistedFields = persisted.getFields() != null ?
                persisted.getFields().stream()
                        .collect(Collectors.toMap(FieldDoc::getName, f -> f, (a, b) -> b)) :
                new HashMap<>();

        List<FieldDoc> mergedFields = new ArrayList<>();
        if (generated.getFields() != null) {
            for (FieldDoc genField : generated.getFields()) {
                FieldDoc persistedField = persistedFields.get(genField.getName());
                if (persistedField != null) {
                    mergedFields.add(mergeField(genField, persistedField, modifiedFields, "fields." + genField.getName()));
                } else {
                    mergedFields.add(genField);
                }
            }
        }

        // Add fields that exist only in persisted (user-added)
        Set<String> genFieldNames = generated.getFields() != null ?
                generated.getFields().stream().map(FieldDoc::getName).collect(Collectors.toSet()) :
                new HashSet<>();
        for (FieldDoc persistedField : persistedFields.values()) {
            if (!genFieldNames.contains(persistedField.getName())) {
                mergedFields.add(persistedField);
            }
        }

        merged.fields(mergedFields);

        meta.setLastGenerated(LocalDateTime.now());
        meta.setVersion(meta.getVersion() + 1);
        merged.persistMeta(meta);

        return merged.build();
    }

    private FieldDoc mergeField(FieldDoc generated, FieldDoc persisted,
                                  Set<String> modifiedFields, String fieldPrefix) {
        FieldDoc.FieldDocBuilder builder = FieldDoc.builder()
                .name(generated.getName())
                .type(generated.getType())
                .fullType(generated.getFullType())
                .entityRef(generated.getEntityRef());

        builder.description(modifiedFields.contains(fieldPrefix + ".description") ?
                persisted.getDescription() : generated.getDescription());
        builder.example(modifiedFields.contains(fieldPrefix + ".example") ?
                persisted.getExample() : generated.getExample());
        builder.required(modifiedFields.contains(fieldPrefix + ".required") ?
                persisted.isRequired() : generated.isRequired());
        builder.defaultValue(modifiedFields.contains(fieldPrefix + ".defaultValue") ?
                persisted.getDefaultValue() : generated.getDefaultValue());
        builder.deprecated(modifiedFields.contains(fieldPrefix + ".deprecated") ?
                persisted.isDeprecated() : generated.isDeprecated());

        return builder.build();
    }

    /**
     * Mark a field as user-modified in the persist meta.
     */
    public void markFieldModified(String signature, String fieldPath) {
        ApiDoc api = apiCache.get(signature);
        if (api == null) return;

        PersistMeta meta = api.getPersistMeta();
        if (meta == null) {
            meta = PersistMeta.builder()
                    .userModified(true)
                    .modifiedFields(new HashSet<>())
                    .build();
            api.setPersistMeta(meta);
        }
        meta.setUserModified(true);
        meta.getModifiedFields().add(fieldPath);
        meta.setLastModified(LocalDateTime.now());
        meta.setVersion(meta.getVersion() + 1);

        // Re-save
        String controllerDir = SignatureUtil.sanitizeFileName(api.getControllerName());
        saveApi(controllerDir, api);
    }

    /**
     * Delete persisted data for a controller.
     */
    public void deleteController(String controllerName) {
        String dirName = SignatureUtil.sanitizeFileName(controllerName);
        Path ctrlDir = controllersDir.resolve(dirName);
        try {
            if (Files.exists(ctrlDir)) {
                Files.walk(ctrlDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
            // Remove from cache
            controllerCache.entrySet().removeIf(e -> e.getValue().getName().equals(controllerName));
        } catch (IOException e) {
            log.error("Error deleting controller dir: {}", ctrlDir, e);
        }
    }

    /**
     * Delete persisted data for an entity.
     */
    public void deleteEntity(String entityName) {
        String fileName = SignatureUtil.sanitizeFileName(entityName) + ".json";
        Path entityPath = entitiesDir.resolve(fileName);
        try {
            Files.deleteIfExists(entityPath);
            entityCache.entrySet().removeIf(e -> e.getValue().getName().equals(entityName));
        } catch (IOException e) {
            log.error("Error deleting entity: {}", entityPath, e);
        }
    }

    /**
     * Get the data directory path.
     */
    public Path getDataDir() {
        return dataDir;
    }
}
