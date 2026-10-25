package dev.novel;

import dev.novel.agent.*;
import dev.novel.agent.runtime.StoryReadTools;
import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:context-budget;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class ContextBudgetIntegrationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired Json json;
    @Autowired ProjectService projects;
    @Autowired MemoryService memory;
    @Autowired AgentContextService context;
    @Autowired ContextBudget budget;
    @Autowired TruthDocumentService truth;

    @Test void workflowApprovalRemainsExplicitWhenThePlanContainsProposalWording() {
        String id=novel();
        var plan=(com.fasterxml.jackson.databind.node.ObjectNode)json.read(Store.string(store.novel(id),"plan"));
        plan.put("stageGoal","待人工审核的阶段规划提案：调查封条来源");
        store.update("novels.awaitPlan",json.write(plan),id);
        String taskId=store.enqueue(id,"WRITE",1001,0,0,UUID.randomUUID().toString(),Map.of());
        var task=store.one("tasks.find",taskId);
        assertThat(json.read(context.context(task,AgentInstructions.storyFiles())).path("_workflow").path("planStatus").asText())
                .isEqualTo("PENDING_APPROVAL");
        projects.approvePlan(id,null);
        var approved=json.read(context.context(task,AgentInstructions.storyFiles()));
        assertThat(approved.path("_workflow").path("planStatus").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("outline.md").path("stageGoal").asText()).contains("待人工审核");
    }

    private String novel() {
        String id=projects.create("长期连载预算验证","林舟调查钥匙来源","人物必须保留知识边界",4,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        var chapters=new ArrayList<Map<String,Object>>();
        for(int chapter=1001;chapter<=1004;chapter++) chapters.add(Map.of("number",chapter,"title","密信"+chapter,
                "goal","核查钥匙与封条","characters",List.of("林舟"),"foreshadowIds",List.of()));
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","核查信件来源","coreMilestones",List.of("找到寄信人"),"chapters",chapters)),id);
        db.update("UPDATE novels SET chapter_no=1001 WHERE id=?",id);
        return id;
    }

    private void append(String id,int first,int last) {
        String evidence="林舟检查钥匙和封条，记录每个缺口的位置。".repeat(160);
        for(int chapter=first;chapter<=last;chapter++) {
            var delta=json.tree(Map.of("summary","林舟核查信件", "facts",List.of(Map.of("key","fact"+chapter,"kind","CHARACTER",
                    "name","林舟","detail","林舟核查第"+chapter+"份信件。".repeat(80),"evidence",evidence)),
                    "relations",List.of(Map.of("subject","林舟","predicate","KNOWS","object","线索"+chapter,"detail","核对信件来源","evidence",evidence))));
            memory.commit(id,chapter,chapter,memory.validateDelta(delta,evidence));
        }
        db.update("UPDATE novels SET memory_version=? WHERE id=?",last,id);
    }

    @Test void hundredsOfHistoricalRecordsKeepRoleContextWithinItsFixedBudget() {
        String id=novel();append(id,1,80);
        String taskId=store.enqueue(id,"WRITE",1001,0,80,UUID.randomUUID().toString(),Map.of());
        var task=store.one("tasks.find",taskId);
        var files=AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md","style_guide.md");
        var before=json.read(context.context(task,files));
        assertThat(budget.bytes(before)).isLessThanOrEqualTo(before.path("_contextBudget").path("limitBytes").asInt());
        append(id,81,320);
        var current=new LinkedHashMap<>(task);current.put("memory_version",320L);
        var after=json.read(context.context(current,files));
        assertThat(budget.bytes(after)).isLessThanOrEqualTo(after.path("_contextBudget").path("limitBytes").asInt());
        assertThat(after.path("_contextBudget").path("omittedRecords").toString()).doesNotContain(":-1");
        assertThat(after.path("outline.md").path("chapters")).hasSize(2);
        assertThat(after.path("outline.md").path("stageGoal").asText()).isEqualTo("核查信件来源");
        assertThat(after.path("story_bible.md").path("bible").asText()).isEqualTo("人物必须保留知识边界");
        assertThat(after.toString()).doesNotContain("\"evidence\":");
        assertThat(memory.snapshot(id,320)).hasSize(640);
        assertThat(memory.snapshot(id,320).stream().map(row->Store.string(row,"content"))).anyMatch(content->content.contains("evidence"));
        assertThat(truth.files(id).get("MEMORY.md")).contains("characters/INDEX.md");
        assertThat(truth.files(id).values()).anyMatch(content->content.contains("memory-record") && content.contains("evidence"));
    }

    @Test void toolReadsUsePagesAndCanRetrieveOriginalEvidenceByExactKey() {
        String id=novel();append(id,1,18);
        String task=store.enqueue(id,"WRITE",1001,0,18,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        var page=json.read(tools.memory("林舟",0,3,null,null));
        assertThat(page.path("facts").size()).isBetween(1,3);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(budget.bytes(page)).isLessThan(6000);
        assertThat(page.toString()).doesNotContain("\"evidence\":");
        var first=json.read(tools.memory("fact18",null,null,"/evidence",0));
        var next=json.read(tools.memory("fact18",null,null,"/evidence",first.path("nextCharacterOffset").asInt()));
        String original=json.read(memory.snapshot(id,18).stream().filter(row->"fact18".equals(row.get("memory_key"))).findFirst().orElseThrow().get("content").toString()).path("evidence").asText();
        assertThat(original).startsWith(first.path("text").asText()+next.path("text").asText());
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        tools.check();
    }

    @Test void veryLargeSingleFactsExposeFieldPagingAndUnicodePositions() {
        String id=novel();
        String detail="林舟😀记录钥匙的来源。".repeat(3000);
        memory.commit(id,1,1,json.tree(Map.of("summary","检查钥匙","facts",List.of(Map.of("key","large","kind","CHARACTER",
                "name","林舟","detail",detail,"evidence","林舟检查钥匙。")),"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        var novel=store.novel(id);
        var page=memory.recall(novel,1001,"large",0,1,"",0,2000);
        assertThat(json.tree(page).path("requiresFieldRead").path("key").asText()).isEqualTo("large");
        var field=json.tree(memory.recall(novel,1001,"large",0,1,"/detail",3,2000));
        String text=field.path("text").asText();
        int start=detail.offsetByCodePoints(0,3),end=detail.offsetByCodePoints(0,field.path("nextCharacterOffset").asInt());
        assertThat(text).isEqualTo(detail.substring(start,end));
        assertThat(budget.bytes(field)).isLessThanOrEqualTo(2000);
    }
}
