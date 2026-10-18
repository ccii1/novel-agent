package dev.novel;

import dev.novel.agent.ReviewValidator;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
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
}
