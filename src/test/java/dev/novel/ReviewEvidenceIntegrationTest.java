package dev.novel;

import dev.novel.agent.ReviewValidator;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:review-evidence;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class ReviewEvidenceIntegrationTest {
    @Autowired ReviewValidator validator;
    @Autowired Json json;

    @Test void joinedQuotesReportWhichIssueCannotMatchTheChapter() {
        var result=json.read("""
                {"approved":false,"majorChange":false,"issues":[
                {"severity":"minor","quote":"林舟交出钥匙；沈月收起密信","reason":"两处表达需要核对","suggestion":"检查动作顺序"}]}
                """);
        assertThatThrownBy(()->validator.validate(result,"林舟交出钥匙。沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第1条","连续","模型响应已保存");
    }

    @Test void separateOriginalQuotesCanSupportDifferentIssues() {
        var result=json.read("""
                {"approved":false,"majorChange":false,"issues":[
                {"severity":"minor","quote":"林舟交出钥匙","reason":"动作缺少铺垫","suggestion":"补充原因"},
                {"severity":"minor","quote":"沈月收起密信","reason":"动机需要说明","suggestion":"补充反应"}]}
                """);
        assertThatCode(()->validator.validate(result,"林舟交出钥匙。沈月收起密信。")).doesNotThrowAnyException();
    }
    @Test void paragraphWhitespaceAndExtraQuotesAreRestoredWithoutChangingOriginalReport() {
        var review=json.read("""
                {"approved":false,"majorChange":false,"issues":[{"quote":"“林舟交出钥匙。沈月收起密信。”","reason":"核对动作","suggestion":"说明动机"}]}
                """);
        var before=review.deepCopy();
        var result=validator.validate(review,"林舟交出钥匙。\n\n沈月收起密信。");
        assertThat(result.path("issues").get(0).path("quote").asText()).isEqualTo("林舟交出钥匙。\n\n沈月收起密信。");
        assertThat(result.path("issues").get(0).path("quoteSource").asText()).isEqualTo("BODY");
        assertThat(review).isEqualTo(before);
    }
    @Test void memoryQuotesRequireAnAuthorizedMemorySourceAndRecordTheirPath() {
        var review=json.read("""
                {"approved":false,"majorChange":false,"issues":[{"quote":"人为防止居民逃跑","quoteSource":"MEMORY","quotePath":"/summary","reason":"人物只提出推测","suggestion":"保留不确定性"}]}
                """);
        var memory=json.read("{\"summary\":\"陆禾判断出口被人为防止居民逃跑。\",\"facts\":[],\"relations\":[]}");
        var result=validator.validate(review,"陆禾判断这些出口条件是在防人跑。",memory);
        assertThat(result.path("issues").get(0).path("quoteSource").asText()).isEqualTo("MEMORY");
        assertThat(result.path("issues").get(0).path("quotePath").asText()).isEqualTo("/summary");
        assertThatThrownBy(()->validator.validate(review,"陆禾判断这些出口条件是在防人跑。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("来源字段不存在");
    }
    @Test void authorizedParagraphPathSuppliesExactEvidenceWithoutModelTranscription() {
        var review=json.read("""
                {"approved":false,"majorChange":false,"issues":[{"quoteSource":"BODY","quotePath":"/paragraphs/p1","reason":"需要核对动作","suggestion":"补充原因"}]}
                """);
        var result=validator.validate(review,"林舟交出钥匙。\n\n沈月收起密信。");
        assertThat(result.path("issues").get(0).path("quote").asText()).isEqualTo("沈月收起密信。");
        ((com.fasterxml.jackson.databind.node.ObjectNode)review.path("issues").get(0)).put("quotePath","/paragraphs/p999");
        assertThatThrownBy(()->validator.validate(review,"林舟交出钥匙。\n\n沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("来源字段不存在");
    }
    @Test
    @EnabledIfEnvironmentVariable(named="NOVEL_REVIEW_REPLAY_FILE",matches=".+")
    void savedMemoryReviewCanQuoteTheOriginalMemorySummary() throws Exception {
        var saved=json.read(Files.readString(Path.of(System.getenv("NOVEL_REVIEW_REPLAY_FILE"))));
        var result=validator.validate(saved.path("output"),saved.path("body").asText(),saved.path("delta"));
        assertThat(result.path("issues").get(0).path("quoteSource").asText()).isEqualTo("MEMORY");
        assertThat(result.path("issues").get(0).path("quotePath").asText()).isEqualTo("/summary");
    }
}
