package dev.novel;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.service.*;
import dev.novel.engine.TaskEngine;
import dev.novel.agent.PlanValidator;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:domain;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class DomainIntegrationTest {
    @Autowired TestDatabase store;
    @Autowired Json json;
    @Autowired BudgetService budget;
    @Autowired MemoryService memory;
    @Autowired ProjectService projects;
    @Autowired AgentService agents;
    @Autowired PlanValidator plans;
    @Autowired TaskEngine engine;
    @BeforeEach void clean() {
        for(String table:List.of("events","edit_requests","research_messages","research","model_calls","chapter_metrics","memories","chapter_history","chapters","tasks","novels")) store.update("DELETE FROM "+table);
    }
    private String novel() { return projects.create("密信","围绕失踪密信展开的悬疑故事","人物不得提前得知秘密",2,new BigDecimal("100"),new BigDecimal("5")); }
    private Map<String,Object> task(String novel,int chapter) {
        String id=store.enqueue(novel,"PLAN",chapter,0,0,UUID.randomUUID().toString(),Map.of());
        return store.one("SELECT * FROM tasks WHERE id=?",id);
    }
    @Test void concurrentReservationsRespectMonthlyLimit() throws Exception {
        List<Map<String,Object>> tasks=new ArrayList<>();for(int i=0;i<20;i++) tasks.add(task(null,0));
        ExecutorService executor=Executors.newFixedThreadPool(20);CountDownLatch start=new CountDownLatch(1);
        List<Future<Boolean>> results=new ArrayList<>();
        for(var task:tasks) results.add(executor.submit(()-> { start.await();
            try { budget.reserve(task,new BigDecimal("20"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);return true; }
            catch(BudgetService.BudgetExceeded expected) { return false; }
        }));
        start.countDown();int accepted=0;for(var result:results) if(result.get(15,TimeUnit.SECONDS)) accepted++;
        executor.shutdownNow();
        assertThat(accepted).isEqualTo(15);
        assertThat(budget.used("billing_month=?",budget.month())).isEqualByComparingTo("300");
    }
    @Test void chapterLimitIncludesOtherTasksReservations() {
        String id=novel();var first=task(id,1);var second=task(id,1);
        budget.reserve(first,new BigDecimal("4"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);
        assertThatThrownBy(()->budget.reserve(second,new BigDecimal("2"),"configured-model",BigDecimal.ONE,BigDecimal.ONE)).isInstanceOf(BudgetService.BudgetExceeded.class).hasMessageContaining("本章");
        assertThat(store.list("SELECT * FROM model_calls")).hasSize(1);
    }
    @Test void rateLimitDefersWithoutReservingAnotherCall() {
        for(int i=0;i<30;i++) budget.reserve(task(null,0),new BigDecimal("0.1"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);
        var task=task(null,0);
        assertThatThrownBy(()->budget.reserve(task,new BigDecimal("0.1"),"configured-model",BigDecimal.ONE,BigDecimal.ONE))
                .isInstanceOf(BudgetService.RateLimited.class);
        assertThat(store.list("SELECT * FROM model_calls")).hasSize(30);
    }
    @Test void monthChangeDoesNotIncludePreviousMonthUsage() {
        budget.reserve(task(null,0),new BigDecimal("300"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);
        store.update("UPDATE model_calls SET billing_month='2000-01'");
        budget.reserve(task(null,0),new BigDecimal("300"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);
        assertThat(budget.used("billing_month=?",budget.month())).isEqualByComparingTo("300");
    }
    @Test void graphUsesVersionedKnowledgeAndClosedRelationships() {
        String id=novel();String body="林舟将仓库钥匙交给沈月。沈月没有透露密信内容。";
        var delta=json.read("""
            {"summary":"林舟交出钥匙。","facts":[],"relations":[
            {"subject":"林舟","predicate":"TRUSTS","object":"沈月","detail":"交出钥匙","deleted":false,"evidence":"林舟将仓库钥匙交给沈月"},
            {"subject":"沈月","predicate":"KNOWS","object":"密信内容","detail":"尚未透露","deleted":false,"evidence":"沈月没有透露密信内容"}]}
            """);
        memory.validateDelta(delta,body);store.transaction(()-> {memory.commit(id,1,1,delta);return null;});
        var closed=json.read("""
            {"summary":"信任结束。","facts":[],"relations":[{"subject":"林舟","predicate":"TRUSTS","object":"沈月","deleted":true,"detail":"关系结束","evidence":"林舟收回仓库钥匙"}]}
            """);
        memory.validateDelta(closed,"林舟收回仓库钥匙。沈月沉默不语。");
        store.transaction(()-> {memory.commit(id,2,2,closed);return null;});
        assertThat((List<?>)memory.graph(id,1,List.of("林舟"),2).get("edges")).hasSize(2);
        assertThat((List<?>)memory.graph(id,2,List.of(),2).get("edges")).hasSize(1);
        assertThat((List<?>)memory.graph(id,2,List.of("林舟"),2).get("edges")).isEmpty();
    }
    @Test void memoryEvidenceMustExistInActualChapter() {
        var delta=json.read("""
            {"summary":"交出钥匙。","relations":[],"facts":[{"key":"event:key","kind":"EVENT","name":"交钥匙","evidence":"沈月烧毁密信"}]}
            """);
        assertThatThrownBy(()->memory.validateDelta(delta,"林舟将钥匙交给沈月。")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("证据");
    }
    @Test void failedMemoryTransactionDoesNotPublishPartialChanges() {
        String id=novel();var delta=json.read("""
            {"facts":[{"key":"world:city","kind":"WORLD","name":"城镇","evidence":"城镇已经封锁"}],"relations":[]}
            """);
        assertThatThrownBy(()->store.transaction(()-> {
            memory.commit(id,1,1,delta);memory.commit(id,1,1,delta);return null;
        })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(memory.snapshot(id,1)).isEmpty();
    }
    @Test void approvalRequiresAwaitingPlanAndValidSequence() {
        String id=novel();assertThatThrownBy(()->projects.approvePlan(id,null)).isInstanceOf(IllegalStateException.class);
        var plan=json.read("""
            {"stageGoal":"查出密信去向","coreMilestones":["发现钥匙"],"chapters":[
            {"number":1,"title":"钥匙","goal":"引出失踪事件","characters":["林舟"],"foreshadowIds":[]},
            {"number":2,"title":"信封","goal":"发现线索","characters":["沈月"],"foreshadowIds":[]}]}
            """);
        plans.validate(plan,1,2);
        store.update("UPDATE novels SET status='AWAITING_PLAN',plan=? WHERE id=?",json.write(plan),id);
        projects.approvePlan(id,plan);
        assertThat(store.novel(id).get("status")).isEqualTo("WRITING");
        assertThat(store.list("SELECT * FROM tasks WHERE novel_id=? AND kind='WRITE'",id)).hasSize(1);
        assertThatThrownBy(()->plans.validate(plan,2,2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historicalEditRequiresPublishedAcknowledgementAndInvalidatesFollowingChapters() {
        String id=novel();
        store.update("UPDATE novels SET status='AWAITING_STAGE_REVIEW',memory_version=1,chapter_no=3 WHERE id=?",id);
        for(int chapter=1;chapter<=2;chapter++) store.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,summary,status,memory_version,published) VALUES(?,?,1,0,'密信','林舟将钥匙交给沈月。','交出钥匙','ACCEPTED',1,TRUE)",id,chapter);
        var delta=json.read("""
            {"facts":[],"relations":[{"subject":"林舟","predicate":"TRUSTS","object":"沈月","detail":"交出钥匙","deleted":false,"evidence":"林舟将钥匙交给沈月"}]}
            """);
        store.transaction(()-> {memory.commit(id,1,1,delta);return null;});
        var edit=projects.requestEdit(id,1,"林舟保留钥匙，不再信任沈月。");
        assertThatThrownBy(()->projects.approveEdit((String)edit.get("id"),false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("已发布");
        projects.approveEdit((String)edit.get("id"),true);
        assertThat(store.novel(id).get("status")).isEqualTo("PLANNING");
        assertThat(Store.integer(store.novel(id),"chapter_no")).isEqualTo(1);
        assertThat((List<?>)memory.graph(id,2,List.of(),2).get("edges")).isEmpty();
        assertThat((List<?>)memory.graph(id,1,List.of(),2).get("edges")).hasSize(1);
        assertThat(store.list("SELECT * FROM chapters WHERE novel_id=? AND status='INVALIDATED'",id)).hasSize(2);
    }
    @Test void interruptedCallPausesOnlyItsNovelAndKeepsReservation() {
        String first=novel(),second=novel();var task=store.one("SELECT * FROM tasks WHERE novel_id=?",first);
        store.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task.get("id"));
        budget.reserve(task,new BigDecimal("1"),"configured-model",BigDecimal.ONE,BigDecimal.ONE);
        engine.recover();
        assertThat(store.novel(first).get("status")).isEqualTo("ERROR");
        assertThat(store.novel(second).get("status")).isEqualTo("PLANNING");
        assertThat(store.one("SELECT status FROM model_calls WHERE task_id=?",task.get("id")).get("status")).isEqualTo("UNCERTAIN");
        assertThatThrownBy(()->projects.retry((String)task.get("id"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("费用");
        String call=(String)store.one("SELECT id FROM model_calls WHERE task_id=?",task.get("id")).get("id");
        budget.reconcile(call,new BigDecimal("0.4"));projects.retry((String)task.get("id"));
        assertThat(store.novel(first).get("status")).isEqualTo("PLANNING");
        assertThat(budget.used("novel_id=?",first)).isEqualByComparingTo("0.4");
    }
    @Test void simultaneousFailedChecksCanEachBeExplicitlyRetried() {
        String id=novel();store.update("UPDATE novels SET status='ERROR',resume_status='CHECKING' WHERE id=?",id);
        String first=store.enqueue(id,"CHECK_CHARACTER",1,0,0,"check-one",Map.of());
        String second=store.enqueue(id,"CHECK_PLOT",1,0,0,"check-two",Map.of());
        store.update("UPDATE tasks SET status='ERROR' WHERE id IN (?,?)",first,second);
        projects.retry(first);projects.retry(second);
        assertThat(store.list("SELECT * FROM tasks WHERE novel_id=? AND kind LIKE 'CHECK%' AND status='WAITING'",id)).hasSize(2);
        assertThat(store.novel(id).get("status")).isEqualTo("CHECKING");
    }
    @Test void sourceCollectionRejectsNonPublicHostsBeforeNetworkAccess() {
        String id=store.enqueue(null,"RESEARCH",0,0,0,"private-url",Map.of("urls",List.of("http://127.0.0.1:8096/api/budget")));
        var task=store.one("SELECT * FROM tasks WHERE id=?",id);
        assertThatThrownBy(()->agents.execute(task)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仅接受");
        assertThat(store.list("SELECT * FROM model_calls")).isEmpty();
    }
}
