package ink.icoding.marginalia.core.service;

import ink.icoding.marginalia.core.model.ApiDoc;
import ink.icoding.marginalia.core.model.EndpointParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarginaliaServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsGetModelAttributeRecordIntoFormParameters() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        writeSource(sourceRoot, "example/controller/ReviewController.java", """
                package example.controller;

                import example.dto.AiReviewQuery;

                @RestController
                class ReviewController {
                    /** 分页查询待人工复核文件。 */
                    @GetMapping("/pending")
                    public TableData<String> pending(@Valid @ModelAttribute AiReviewQuery query) {
                        return null;
                    }
                }
                """);
        writeSource(sourceRoot, "example/dto/AiReviewQuery.java", """
                package example.dto;

                /**
                 * AI 审批快照分页查询条件。
                 *
                 * @param fileName 文件名，支持模糊匹配
                 * @param projectName 项目名称，支持模糊匹配
                 * @param documentType 文件类型，仅支持规则库的四种类型
                 * @param aiReviewStatus AI 审批状态
                 * @param submittedBy 提交人名称，支持模糊匹配
                 * @param assignee 被指派人名称，支持模糊匹配
                 * @param pageNum 页码，从 1 开始
                 * @param pageSize 每页数量，最大 100
                 */
                public record AiReviewQuery(
                        String fileName,
                        String projectName,
                        DocumentType documentType,
                        AiReviewStatus aiReviewStatus,
                        String submittedBy,
                        String assignee,
                        @Min(1) Integer pageNum,
                        @Min(1) @Max(100) Integer pageSize
                ) {}
                """);

        MarginaliaService service = new MarginaliaService(
                tempDir.resolve("docs").toString(), "example", List.of(sourceRoot.toString()));
        service.scanAndMerge();

        ApiDoc api = service.getAllControllers().get(0).getApis().get(0);
        Map<String, EndpointParam> params = api.getFormParams().stream()
                .collect(Collectors.toMap(EndpointParam::getName, Function.identity()));

        assertEquals(8, params.size());
        assertTrue(api.getQueryParams().isEmpty());
        assertEquals("文件名，支持模糊匹配", params.get("fileName").getDescription());
        assertEquals("DocumentType", params.get("documentType").getType());
        assertFalse(params.get("pageNum").isRequired());
        assertTrue(service.getAllEntities().stream().anyMatch(entity -> entity.getName().equals("AiReviewQuery")));
    }

    private void writeSource(Path sourceRoot, String relativePath, String content) throws Exception {
        Path file = sourceRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
