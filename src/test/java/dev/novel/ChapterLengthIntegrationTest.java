package dev.novel;

import dev.novel.agent.validation.AgentOutputReader;
import dev.novel.dto.AgentOutputs;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.ChapterQualityService;
import dev.novel.service.ProjectService;
import dev.novel.service.WorkflowService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:chapter-length;DB_CLOSE_DELAY=-1","novel.engine-enabled=false","novel.review-mode=combined"})
class ChapterLengthIntegrationTest {
    @Autowired ChapterQualityService quality;
    @Autowired AgentOutputReader outputs;
    @Autowired dev.novel.agent.validation.ChapterOutputValidator chapters;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired Store store;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired Json json;

    @ParameterizedTest
    @ValueSource(strings={"approved","majorChange"})
    void reviewBooleanErrorsIdentifyTheExactField(String field) {
        var review=json.tree(Map.of("approved",false,"majorChange",false,"issues",List.of()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)review).put(field,"无法评估");
        assertThatThrownBy(()->outputs.read(review,AgentOutputs.Review.class))
                .hasMessageContaining("Review."+field,"Boolean");
    }
    @Test void blockedGenerationCannotBeAcceptedAsAChapter() {
        var blocked=json.tree(Map.of("status","BLOCKED","reason","缺少批准章纲","title","执行说明","body","本轮未生成小说正文。"));
        assertThatThrownBy(()->chapters.validate(blocked)).hasMessageContaining("正文未生成","缺少批准章纲");
        assertThatThrownBy(()->chapters.validate(json.tree(Map.of("title","密信","body","林舟检查封条。"))))
                .hasMessageContaining("status");
        assertThat(chapters.validate(json.tree(Map.of("status","GENERATED","title","密信","body","林舟检查封条。"))).body())
                .isEqualTo("林舟检查封条。");
    }

    @ParameterizedTest
    @ValueSource(ints={1200,4500})
    void nonEmptyChaptersOutsideReferenceLengthReceiveAdviceWithoutRejection(int characters) {
        String body="叙".repeat(characters);
        var chapter=json.tree(Map.of("title","密信","body",body));
        assertThat(outputs.read(chapter,AgentOutputs.Chapter.class).body()).isEqualTo(body);
        var review=json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[]}");
        quality.enforceStyle(review,body);
        assertThat(review.path("approved").asBoolean()).isTrue();
        assertThat(review.path("issues")).isEmpty();
        assertThat(review.path("lengthAdvice").path("characters").asInt()).isEqualTo(characters);
        assertThat(review.path("lengthAdvice").path("status").asText()).isEqualTo(characters<2000?"SHORT":"LONG");
    }

    @Test void genuineContentIssuesRemainBlocking() {
        var review=json.read("""
                {"approved":false,"majorChange":false,"issues":[
                {"severity":"minor","quote":"林舟反复查看信封","reason":"同一动作反复出现，情节没有进展","suggestion":"删除重复动作，推进调查"}]}
                """);
        quality.enforceStyle(review,"林舟反复查看信封。林舟反复查看信封。");
        assertThat(review.path("approved").asBoolean()).isFalse();
        assertThat(review.path("issues")).hasSize(1);
    }

    @Test void emptyBodiesRemainInvalid() {
        assertThatThrownBy(()->outputs.read(json.tree(Map.of("title","密信","body","  \n")),AgentOutputs.Chapter.class))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("body");
    }

    @Test
    @EnabledIfEnvironmentVariable(named="NOVEL_CHAPTER_LENGTH_REPLAY_FILE",matches=".+")
    void archivedModelChapterIsSavedBeforeReviewsWithoutTruncation() throws Exception {
        var captured=json.read(Files.readString(Path.of(System.getenv("NOVEL_CHAPTER_LENGTH_REPLAY_FILE"))));
        var chapter=captured.path("output");
        var parsed=outputs.read(chapter,AgentOutputs.Chapter.class);
        assertThat(quality.measure(parsed.body()).characters()).isLessThan(1800);
        String novel=projects.create("篇幅整理验证","保存完整情节","保持人物与关键物证",1,BigDecimal.TEN,BigDecimal.ONE);
        String id=store.enqueue(novel,"WRITE",1,0,0,"length-replay:"+UUID.randomUUID(),Map.of());
        assertThat(dispatch.claim(id)).isEqualTo(1);
        workflow.finished(store.one("tasks.find",id),chapter);
        var saved=store.one("chapters.find",novel,1);
        assertThat(saved.get("body")).isEqualTo(parsed.body());
        assertThat(saved.get("status")).isEqualTo("DRAFT");
        assertThat(store.one("novels.find",novel).get("status")).isEqualTo("CHECKING");
        assertThat(store.list("tasks.byNovel",novel).stream().filter(task->String.valueOf(task.get("kind")).startsWith("CHECK_")))
                .extracting(task->task.get("kind")).containsExactly("CHECK_CHAPTER");
    }
}
