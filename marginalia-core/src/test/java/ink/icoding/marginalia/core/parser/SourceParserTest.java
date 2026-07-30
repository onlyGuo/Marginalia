package ink.icoding.marginalia.core.parser;

import ink.icoding.marginalia.core.model.ApiDoc;
import ink.icoding.marginalia.core.model.ControllerDoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
