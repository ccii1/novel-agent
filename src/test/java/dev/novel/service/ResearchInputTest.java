package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.TestDatabase;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:research-input;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class ResearchInputTest {
    @Autowired ResearchService research;
    @Autowired TestDatabase store;
    @Autowired Json json;
    @Test void ownIdeaCreatesPersistentTaskWithoutSourceLinks() {
        String id=research.create(null,"小城修理工调查旧案，围绕职业技能推动悬疑。");
        var task=store.one("SELECT * FROM tasks WHERE id=?",id);
        assertThat(task.get("status")).isEqualTo("WAITING");
        var payload=json.read((String)task.get("payload"));
        assertThat(payload.path("urls").isEmpty()).isTrue();
        assertThat(payload.path("brief").asText()).contains("修理工");
    }
    @Test void inputRequiresAnIdeaOrPublicReference() {
        assertThatThrownBy(()->research.create(List.of()," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->research.create(List.of("http://localhost:18080"),"悬疑")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->research.create(List.of(),"文".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingReportAndBlankMessagesAreRejected() {
        assertThatThrownBy(()->research.chat("missing"," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->research.chat("missing","调整第二个方向")).isInstanceOf(java.util.NoSuchElementException.class);
    }
}
