package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.service.MemoryService;
import dev.novel.service.ProjectService;
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
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-evidence;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class MemoryEvidenceIntegrationTest {
    @Autowired MemoryService memory;
    @Autowired ProjectService projects;
    @Autowired Json json;

    private JsonNode delta(String fact,String relation) {
        return json.tree(Map.of("summary","林舟交出钥匙，沈月收起密信。",
                "facts",List.of(Map.of("key","character:lin","kind","CHARACTER","name","林舟","evidence",fact)),
                "relations",List.of(Map.of("subject","林舟","predicate","TRUSTS","object","沈月","detail","交出钥匙","evidence",relation))));
    }

    @ParameterizedTest
    @ValueSource(strings={"“%s”","‘%s’","「%s」","『%s』","\"%s\"","'%s'"})
    void firstChapterStoresExactOriginalEvidenceAfterRemovingExtraQuotePair(String wrapper) {
        String novel=projects.create("记忆证据验证","交接钥匙与密信","保持人物知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        assertThat(memory.snapshot(novel,0)).isEmpty();
        var original=delta(wrapper.formatted("林舟交出钥匙"),wrapper.formatted("沈月收起密信"));
        var result=memory.validateDelta(original,"林舟交出钥匙，沈月收起密信。");
        assertThat(original.path("facts").get(0).path("evidence").asText()).isEqualTo(wrapper.formatted("林舟交出钥匙"));
        memory.commit(novel,1,1,result);
        var stored=memory.snapshot(novel,1);
        assertThat(stored).hasSize(2);
        assertThat(stored.stream().map(row->json.read((String)row.get("content")).path("evidence").asText()))
                .containsExactlyInAnyOrder("林舟交出钥匙","沈月收起密信");
        var graph=memory.graph(novel,1,List.of(),2);
        var edges=(List<?>)graph.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(((Map<?,?>)edges.get(0)).get("evidence")).isEqualTo("沈月收起密信");
        assertThat(memory.snapshot(novel,0)).isEmpty();
    }

    @Test void quotationMarksAlreadyPresentInBodyRemainUnchanged() {
        var result=delta("“林舟交出钥匙”","「沈月收起密信」");
        var before=result.deepCopy();
        assertThat(memory.validateDelta(result,"原文写着：“林舟交出钥匙”。随后是「沈月收起密信」。")).isEqualTo(before);
        assertThat(result).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"林舟交出钥匙；沈月收起密信","林舟递出了钥匙","“林舟交出钥匙！”","“林舟交出钥匙"})
    void mismatchedEvidenceReportsRelationAndLeavesEntireDeltaUnchanged(String quote) {
        var result=delta("“林舟交出钥匙”",quote);
        var before=result.deepCopy();
        assertThatThrownBy(()->memory.validateDelta(result,"林舟交出钥匙。沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第1条关系","relation:林舟:TRUSTS:沈月","连续匹配","模型响应已保存");
        assertThat(result).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"“林舟”","“😀😀”"})
    void wrapperCharactersCannotMakeShortEvidenceReachFourCharacters(String quote) {
        var result=delta(quote,"沈月收起密信");
        assertThatThrownBy(()->memory.validateDelta(result,"林舟交出钥匙。😀😀。沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第1条事实","character:lin","不足4个字符");
    }

    @Test
    @EnabledIfEnvironmentVariable(named="NOVEL_MEMORY_REPLAY_FILE",matches=".+")
    void validatesPreviouslyRecordedModelOutputAgainstItsActualChapter() throws Exception {
        var captured=json.read(Files.readString(Path.of(System.getenv("NOVEL_MEMORY_REPLAY_FILE"))));
        JsonNode original=captured.path("output");
        var before=original.deepCopy();
        String body=captured.path("body").asText();
        int originalMismatch=0;
        for(String section:List.of("facts","relations"))
            for(var item:original.path(section)) if(!body.contains(item.path("evidence").asText())) originalMismatch++;
        assertThat(originalMismatch).isPositive();
        var result=memory.validateDelta(original,body);
        for(String section:List.of("facts","relations"))
            for(var item:result.path(section)) assertThat(body).contains(item.path("evidence").asText());
        assertThat(original).isEqualTo(before);
    }
}
