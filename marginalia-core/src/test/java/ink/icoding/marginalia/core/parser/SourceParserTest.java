package ink.icoding.marginalia.core.parser;

import ink.icoding.marginalia.core.model.ApiDoc;
import ink.icoding.marginalia.core.model.ControllerDoc;
import ink.icoding.marginalia.core.model.EntityDoc;
import ink.icoding.marginalia.core.model.FieldDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceParserTest {

    @TempDir
    Path tempDir;

    @Test
    void usesFirstJavadocParagraphAsApiName() throws Exception {
        Path source = tempDir.resolve("ReviewController.java");
        Files.writeString(source, """
                package example;

                @RestController
                class ReviewController {
                    /**
                     * 启动批量智能解析、分类和规则审批。
                     *
                     * <p>SSE 依次发送 {@code progress}、{@code file-result} 和 {@code completed}
                     * 事件；单文件结果包含审批快照主键和版本号，供后续批量确认使用。</p>
                     */
                    @PostMapping(value = "/intelligent-review", produces = "text/event-stream")
                    public SseEmitter intelligentReview(@Valid @RequestBody BatchDocumentRequest request) {
                        return null;
                    }
                }
                """);

        List<ControllerDoc> controllers = new SourceParser().parseSourceFile(source, "example");

        assertEquals(1, controllers.size());
        ApiDoc api = controllers.get(0).getApis().get(0);
        assertEquals("启动批量智能解析、分类和规则审批。", api.getName());
        assertTrue(api.getDescription().contains("SSE 依次发送"));
        assertTrue(api.getDescription().contains("{@code completed}"));
    }

    @Test
    void readsJavadocLineBlockAndTrailingCommentsFromRecordComponents() {
        EntityDoc entity = new SourceParser().parseEntityFromContent("""
                package example;

                public record WarningRuleRequest(
                        /** 规则名称，不能为空。 */
                        @NotBlank String name,
                        /**
                         * 正数触发阈值；
                         * 必须大于零。
                         */
                        BigDecimal thresholdValue,
                        // 风险等级第一行
                        // 风险等级第二行
                        WarningRiskLevel riskLevel,
                        /*
                         * 是否启用；
                         * 为空时按启用处理。
                         */
                        Boolean enabled,
                        @Schema(description = "Swagger 后备说明") String schemaOnly,
                        String trailingComment // 尾部行注释
                ) {}
                """, "WarningRuleRequest");

        Map<String, FieldDoc> fields = fieldsByName(entity);
        assertEquals("规则名称，不能为空。", fields.get("name").getDescription());
        assertEquals("正数触发阈值； 必须大于零。", fields.get("thresholdValue").getDescription());
        assertEquals("风险等级第一行 风险等级第二行", fields.get("riskLevel").getDescription());
        assertEquals("是否启用； 为空时按启用处理。", fields.get("enabled").getDescription());
        assertEquals("Swagger 后备说明", fields.get("schemaOnly").getDescription());
        assertEquals("尾部行注释", fields.get("trailingComment").getDescription());
    }

    @Test
    void fallsBackToMultilineRecordParamDocumentation() {
        EntityDoc entity = new SourceParser().parseEntityFromContent("""
                package example;

                /**
                 * 查询条件。
                 *
                 * @param keyword 关键字，支持名称、编码
                 *                和备注的模糊匹配
                 * @param pageNum 页码
                 */
                public record Query(String keyword, Integer pageNum) {}
                """, "Query");

        Map<String, FieldDoc> fields = fieldsByName(entity);
        assertEquals("关键字，支持名称、编码 和备注的模糊匹配", fields.get("keyword").getDescription());
        assertEquals("页码", fields.get("pageNum").getDescription());
    }

    @Test
    void readsCommonCommentStylesFromClassFields() {
        EntityDoc entity = new SourceParser().parseEntityFromContent("""
                package example;

                public class Result {
                    /** 标准字段文档。 */
                    private String documented;

                    // 第一行
                    // 第二行
                    private String lineComment;

                    /* 普通块注释。 */
                    private String blockComment;

                    private String trailingComment; // 尾部注释。
                }
                """, "Result");

        Map<String, FieldDoc> fields = fieldsByName(entity);
        assertEquals("标准字段文档。", fields.get("documented").getDescription());
        assertEquals("第一行 第二行", fields.get("lineComment").getDescription());
        assertEquals("普通块注释。", fields.get("blockComment").getDescription());
        assertEquals("尾部注释。", fields.get("trailingComment").getDescription());
        assertNull(entity.getDescription());
    }

    @Test
    void doesNotUseMethodBodyCommentsAsEndpointDocumentation() throws Exception {
        Path source = tempDir.resolve("StatusController.java");
        Files.writeString(source, """
                package example;

                @RestController
                class StatusController {
                    @GetMapping("/status")
                    public String status() {
                        // Implementation detail, not API documentation.
                        return "ok";
                    }
                }
                """);

        ApiDoc api = new SourceParser().parseSourceFile(source, "example")
                .get(0).getApis().get(0);

        assertEquals("status", api.getName());
        assertEquals("status", api.getDescription());
    }

    private Map<String, FieldDoc> fieldsByName(EntityDoc entity) {
        return entity.getFields().stream()
                .collect(Collectors.toMap(FieldDoc::getName, Function.identity()));
    }
}
