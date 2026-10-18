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
    @ValueSource(strings={"\n\n","\r\n\r\n","\t ","\u3000","\u00a0"})
    void whitespaceDifferencesRestoreBodyEvidenceInDatabaseAndGraph(String whitespace) {
        String fact="三扇门，三种锁法，坏掉的部件也不同。"+whitespace+"可条件一模一样。";
        String relation="沈月"+whitespace+"收起密信";
        String body=fact+"林舟交出钥匙。"+relation+"。";
        var original=delta("“三扇门，三种锁法，坏掉的部件也不同。可条件一模一样。”","沈月收起密信");
        var before=original.deepCopy();
        var result=memory.validateDelta(original,body);
        assertThat(result.path("facts").get(0).path("evidence").asText()).isEqualTo(fact);
        assertThat(result.path("relations").get(0).path("evidence").asText()).isEqualTo(relation);
        assertThat(original).isEqualTo(before);
        String novel=projects.create("段落证据验证","出城条件","保持证据原文",1,BigDecimal.TEN,BigDecimal.ONE);
        memory.commit(novel,1,1,result);
        assertThat(memory.snapshot(novel,1).stream().map(row->json.read((String)row.get("content")).path("evidence").asText()))
                .containsExactlyInAnyOrder(fact,relation);
        var edges=(List<?>)memory.graph(novel,1,List.of(),2).get("edges");
        assertThat(((Map<?,?>)edges.get(0)).get("evidence")).isEqualTo(relation);
    }

    @Test void modelWhitespaceCanDifferWhileBodyQuotesRemainPresent() {
        var result=memory.validateDelta(delta("“林舟\n交出钥匙”","沈月\t收起密信"),"“林舟交出钥匙”。沈月收起密信。");
        assertThat(result.path("facts").get(0).path("evidence").asText()).isEqualTo("“林舟交出钥匙”");
        assertThat(result.path("relations").get(0).path("evidence").asText()).isEqualTo("沈月收起密信");
    }

    @Test void whitespaceCannotHideOmittedTextOrChangedPunctuation() {
        for(String evidence:List.of("林舟交出钥匙。沈月收起密信","林舟交出钥匙；沈月收起密信")) {
            assertThatThrownBy(()->memory.validateDelta(delta(evidence,"沈月收起密信"),"林舟交出钥匙。\n守卫转身离开。\n沈月收起密信。"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("连续匹配");
        }
    }

    @Test void whitespaceDoesNotCountTowardMinimumEvidenceLength() {
        assertThatThrownBy(()->memory.validateDelta(delta("林\n\n舟","沈月收起密信"),"林\n\n舟交出钥匙。沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不足4个字符");
    }

    @Test void omittedDialogueQuotesRestoreTheActualContinuousBodySpan() {
        String body="她问：“每个门都一样？”\n\n陆砚点头。沈月收起密信。";
        var result=memory.validateDelta(delta("每个门都一样？\n\n陆砚点头。","沈月收起密信"),body);
        assertThat(result.path("facts").get(0).path("evidence").asText()).isEqualTo("每个门都一样？”\n\n陆砚点头。");
        assertThatThrownBy(()->memory.validateDelta(delta("每个门都一样。陆砚点头。","沈月收起密信"),body))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("连续匹配");
    }
    @Test void paragraphReferencesSupplyEvidenceAndLeaveTheRecordedResponseUnchanged() {
        var original=delta("林舟交出钥匙","沈月收起密信");
        var fact=(com.fasterxml.jackson.databind.node.ObjectNode)original.path("facts").get(0);
        fact.remove("evidence");fact.put("evidencePath","/paragraphs/p0");
        var before=original.deepCopy();
        var result=memory.validateDelta(original,"林舟交出钥匙。\n\n沈月收起密信。");
        assertThat(result.path("facts").get(0).path("evidence").asText()).isEqualTo("林舟交出钥匙。");
        assertThat(result.path("facts").get(0).has("evidencePath")).isFalse();
        assertThat(original).isEqualTo(before);
        fact.put("evidencePath","/paragraphs/p999");
        assertThatThrownBy(()->memory.validateDelta(original,"林舟交出钥匙。\n\n沈月收起密信。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("正文段落不存在");
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
    @EnabledIfEnvironmentVariable(named="NOVEL_MEMORY_WHITESPACE_REPLAY_FILE",matches=".+")
    void recordedParagraphEvidenceMatchesWhileChangedPunctuationRemainsRejected() throws Exception {
        var captured=json.read(Files.readString(Path.of(System.getenv("NOVEL_MEMORY_WHITESPACE_REPLAY_FILE"))));
        JsonNode original=captured.path("output");
        String body=captured.path("body").asText();
        var before=original.deepCopy();
        var factsOnly=original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)factsOnly).putArray("relations");
        var result=memory.validateDelta(factsOnly,body);
        var fact=result.path("facts").get(3);
        assertThat(fact.path("key").asText()).isEqualTo("WORLD_EXIT_COLLAPSE_RULE");
        assertThat(fact.path("evidence").asText()).isEqualTo("三扇门，三种锁法，坏掉的部件也不同。\n\n可条件一模一样。");
        for(var item:result.path("facts")) assertThat(body).contains(item.path("evidence").asText());
        assertThatThrownBy(()->memory.validateDelta(original,body))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("第1条关系","SIBLING_OF","他和妹妹贴着墙根走。");
        assertThat(original).isEqualTo(before);
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
