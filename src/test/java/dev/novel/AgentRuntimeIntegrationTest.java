package dev.novel;

import dev.novel.agent.AgentContextService;
import dev.novel.agent.runtime.StoryReadTools;
import dev.novel.agent.orchestration.ReviewOrchestrator;
import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:agent-runtime;DB_CLOSE_DELAY=-1","novel.engine-enabled=false","novel.model.requests-per-minute=2"})
class AgentRuntimeIntegrationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired AgentService agents;
    @Autowired AgentContextService context;
    @Autowired MemoryService memory;
    @Autowired ProjectService projects;
    @Autowired CoordinationService coordination;
    @Autowired ReviewOrchestrator reviews;
    @Autowired Json json;
    @Autowired BudgetService budget;
    @Autowired dev.novel.repository.mapper.ModelCallMapper calls;
    private String novel() {
        String id=projects.create("角色权限验证","调查封条","保留人物知识范围",1,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        store.update("novels.awaitPlan","{\"stageGoal\":\"调查来源\",\"coreMilestones\":[\"找到封条\"],\"chapters\":[{\"number\":1,\"title\":\"封条\",\"goal\":\"核对印章\",\"characters\":[\"陆禾\"],\"foreshadowIds\":[\"seal\"]}]}",id);
        store.update("chapters.draft",id,1,1,0,"封条","陆禾查看封条。\n\n红印仍未干透。",0);
        return id;
    }
    @Test void rolesDeclareIndependentToolPermissionsAndIterationLimits() {
        assertThat(agents.runtime("COORDINATE").tools()).contains("delegate_outline","delegate_memory","delegate_draft");
        assertThat(agents.runtime("WRITE").tools()).containsExactlyInAnyOrder("read_memory","read_relations","list_memory_files","search_memory","lookup_memory","read_source","read_candidate_memory","read_memory_batch");
        assertThat(agents.runtime("CHECK_PLOT").tools()).containsExactlyInAnyOrder("read_memory","read_paragraph","list_memory_files","search_memory","lookup_memory","read_source","read_candidate_memory","read_memory_batch");
        assertThat(agents.runtime("MEMORY").tools()).doesNotContain("delegate_draft","delegate_outline");
        assertThat(agents.runtime("COORDINATE").maxIterations()).isEqualTo(2);
        assertThat(agents.runtime("WRITE").maxIterations()).isEqualTo(8);
        assertThat(agents.runtime("COORDINATE").maxRequests()).isEqualTo(7);
        assertThat(agents.runtime("RESEARCH").maxRequests()).isEqualTo(5);
        assertThat(agents.runtime("WRITE").maxRequests()).isEqualTo(13);
    }
    @Test void toolsReadActualParagraphAndRejectStaleVersions() {
        String id=novel();
        String task=store.enqueue(id,"CHECK_PLOT",1,0,0,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        assertThat(json.read(tools.paragraph("p1")).path("text").asText()).isEqualTo("红印仍未干透。");
        var recalled=json.read(tools.memory());
        assertThat(recalled.has("facts")).isTrue();
        assertThat(recalled.has("approvedPlan")).isFalse();
        assertThatThrownBy(()->tools.paragraph("p999")).hasMessageContaining("不存在");
        assertThatThrownBy(tools::check).hasMessageContaining("不存在");
        var stale=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        assertThatThrownBy(stale::memory).hasMessageContaining("记忆版本已变化");
    }
    @Test void coordinatorOnlyCreatesSuggestionUntilHumanApproval() {
        String id=novel();String before=Store.string(store.novel(id),"plan");
        String task=coordination.request(id,"延后伏笔揭示");
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        tools.outline("/chapters/0/foreshadowIds","保留伏笔，延后揭示");
        assertThat(store.novel(id)).containsEntry("plan",before).containsEntry("status","AWAITING_PLAN");
        assertThat(store.list("planChanges.list",id)).isEmpty();
        assertThatThrownBy(()->tools.draft("修改正文")).hasMessageContaining("只能选择一个");
        assertThatThrownBy(()->coordination.approve(id,task)).hasMessageContaining("等待");
    }
    @Test void tenSmallMemoryReadsCompleteWithinTheSameTaskBudget() {
        String id=novel();
        var facts=new ArrayList<Map<String,Object>>();
        for(int index=0;index<8;index++) facts.add(Map.of("key","character-"+index,"kind","CHARACTER","name","人物"+index,
                "detail","第"+index+"个人物掌握钥匙线索。","evidence","人物核对钥匙。"));
        memory.commit(id,1,1,json.tree(Map.of("summary","核对钥匙","facts",facts,"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String taskId=store.enqueue(id,"WRITE",2,0,1,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",taskId),context,memory,store,json);
        var directory=json.read(tools.files("characters",0,10));
        tools.files("hooks",0,10);
        for(var row:directory.path("files")) {
            if(row.path("path").asText().endsWith("/INDEX.md")) continue;
            var result=json.read(tools.file(row.path("path").asText(),0,1500));
            assertThat(result.path("content").asText()).contains("钥匙线索");
        }
        tools.check();
        var events=db.list("SELECT message FROM events WHERE task_id=? AND type='AGENT_TOOL_USED' ORDER BY id",taskId);
        assertThat(events).hasSize(10);
        var last=json.read(Store.string(events.get(9),"message"));
        assertThat(last.path("toolCall").asInt()).isEqualTo(10);
        assertThat(last.path("remainingToolBytes").asInt()).isPositive();
    }
    @Test void cumulativeToolBytesReportExhaustionAndNextRequestRestoresSpace() {
        String id=novel();String taskId=store.enqueue(id,"CHECK_PLOT",1,0,0,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",taskId),context,memory,store,json,1024);
        for(int index=0;index<100;index++) tools.paragraph("p1");
        assertThat(json.read(tools.paragraph("p1")).path("status").asText()).isEqualTo("BUDGET_EXHAUSTED");
        tools.check();
        tools.reserveRequestSpace(40000,120000);
        assertThat(json.read(tools.paragraph("p1")).path("text").asText()).isEqualTo("红印仍未干透。");
    }
    @Test void defaultReadReturnsMoreTextAndReportsRemainingRequestAndByteBudgets() {
        String id=novel();
        memory.commit(id,1,1,json.tree(Map.of("summary","核对钥匙","facts",List.of(Map.of("key","large-person","kind","CHARACTER",
                "name","林舟","detail","林舟😀核对钥匙的来源。".repeat(500),"evidence","林舟查看封条。")),"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String task=store.enqueue(id,"WRITE",2,0,1,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        tools.reserveRequestSpace(40000,120000);
        String response=tools.file(memory.files().legacyPath("large-person","CHARACTER"),null,null);
        assertThat(response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isBetween(12001,24000);
        var value=json.read(response);
        assertThat(value.path("nextOffset").asInt()).isEqualTo(6000);
        assertThat(value.path("hasMore").asBoolean()).isTrue();
        assertThat(value.path("_toolBudget").path("remainingModelRequests").asInt()).isEqualTo(3);
        assertThat(value.path("_toolBudget").path("remainingBytes").asInt()).isBetween(24001,59999);
    }
    @Test void batchReadSharesBudgetAndIndividualReadsContinueAtUnicodeOffsets() {
        String id=novel();var facts=new ArrayList<Map<String,Object>>();var paths=new ArrayList<String>();
        for(int index=0;index<3;index++) {
            String key="large-"+index;paths.add(memory.files().legacyPath(key,"CHARACTER"));
            facts.add(Map.of("key",key,"kind","CHARACTER","name","人物"+index,"detail","人物😀核对封条。".repeat(100),"evidence","查看封条。"));
        }
        memory.commit(id,1,1,json.tree(Map.of("summary","核对封条","facts",facts,"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String task=store.enqueue(id,"WRITE",2,0,1,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json,4096,60000,4);
        var batch=json.read(tools.batch(paths,0,6000));
        assertThat(batch.path("files")).hasSize(3);assertThat(batch.path("hasMore").asBoolean()).isTrue();
        assertThat(json.write(batch).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        var first=batch.path("files").get(0);String path=first.path("path").asText();
        var next=json.read(tools.file(path,first.path("nextOffset").asInt(),100));
        String original=memory.files().readFull(id,1,path);
        assertThat(original).startsWith(first.path("content").asText()+next.path("content").asText());
        tools.check();
        var missing=json.read(tools.batch(List.of(paths.get(0),"characters/missing.md"),0,10));
        assertThat(missing.path("missingPaths")).containsExactly(json.tree("characters/missing.md"));
    }
    @Test void requestHeadroomStillRestrictsToolReads() {
        String id=novel();String taskId=store.enqueue(id,"CHECK_PLOT",1,0,0,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",taskId),context,memory,store,json);
        tools.reserveRequestSpace(119900,120000);
        assertThat(json.read(tools.paragraph("p1")).path("status").asText()).isEqualTo("BUDGET_EXHAUSTED");
        tools.check();
    }
    @Test void reviewRoundRequiresAllRolesAndSameRevision() {
        String id=novel();var novel=store.novel(id);
        reviews.start(novel,1,0);reviews.start(novel,1,0);
        assertThat(store.list("tasks.chapterChecks",id,1,0,0)).singleElement().satisfies(row->assertThat(row).containsEntry("kind","CHECK_CHAPTER"));
        assertThat(reviews.collect(novel,1,0).ready()).isFalse();
        assertThat(reviews.collect(novel,1,1).completed()).isEmpty();
    }
    @Test void coordinatorRejectsInvalidScopeAndMemoryRouteWithoutPendingReview() {
        String id=novel();
        assertThatThrownBy(()->coordination.validate(id,json.tree(Map.of("target","OUTLINE","path","/chapters")))).hasMessageContaining("范围无效");
        assertThatThrownBy(()->coordination.validate(id,json.tree(Map.of("target","MEMORY")))).hasMessageContaining("没有待处理");
    }
    @Test void eachTurnReservesRateWithoutDoubleCountingParent() {
        String id=novel();String task=coordination.request(id,"修改伏笔");
        budget.reserve(store.one("tasks.find",task),BigDecimal.ZERO,"accounting-validation",BigDecimal.ZERO,BigDecimal.ZERO);
        String call=db.one("SELECT id FROM model_calls WHERE task_id=?",task).get("id").toString();
        assertThat(calls.recentCount()).isEqualTo(1);
        budget.recordTurn(UUID.randomUUID().toString(),call,task,1,"{}");
        assertThat(calls.recentCount()).isEqualTo(1);
        budget.recordTurn(UUID.randomUUID().toString(),call,task,2,"{}");
        assertThat(calls.recentCount()).isEqualTo(2);
        assertThatThrownBy(()->budget.recordTurn(UUID.randomUUID().toString(),call,task,3,"{}")).isInstanceOf(BudgetService.RateLimited.class);
        assertThat(((Number)agents.metrics(id).get(0).get("model_calls")).intValue()).isEqualTo(2);
    }
}
