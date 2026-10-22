package dev.novel;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.service.ProjectService;
import dev.novel.service.BudgetService;
import dev.novel.service.ResearchService;
import dev.novel.integration.model.ModelCatalog;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:selection;DB_CLOSE_DELAY=-1","novel.engine-enabled=false",
        "novel.default-provider=local-test","novel.providers.local-test.endpoint=http://localhost:18080/v1",
        "novel.providers.local-test.api-key=test-configuration-only","novel.providers.local-test.name=text-model",
        "novel.providers.local-test.billing-mode=unmetered",
        "novel.providers.local-test.reasoning-efforts[0]=low","novel.providers.local-test.reasoning-efforts[1]=medium",
        "novel.providers.local-test.reasoning-efforts[2]=high","novel.providers.local-test.reasoning-effort=medium",
        "novel.providers.other-test.endpoint=http://localhost:18081/v1","novel.providers.other-test.api-key=test-configuration-only",
        "novel.providers.other-test.name=another-text-model","novel.providers.other-test.billing-mode=unmetered"})
class ModelSelectionTest {
    @Autowired TestDatabase store;
    @Autowired ProjectService projects;
    @Autowired ModelCatalog catalog;
    @Autowired BudgetService budget;
    @Autowired Json json;
    @Autowired ResearchService research;
    @BeforeEach void clean() {
        for(String table:List.of("events","research_messages","research","model_calls","tasks","novels")) store.update("DELETE FROM "+table);
    }
    @Test void publicCatalogKeepsCredentialsPrivateAndUsesFreeProfile() {
        var selection=catalog.resolve(null,"selected-text-model","WRITE");
        assertThat(selection.ready()).isTrue();
        assertThat(selection.model()).isEqualTo("selected-text-model");
        assertThat(selection.inputPrice()).isZero();
        assertThat(selection.outputPrice()).isZero();
        assertThat(json.write(catalog.publicProfiles())).contains("local-test","unmetered")
                .doesNotContain("test-configuration-only","localhost","apiKey","endpoint");
    }
    @Test void reasoningSelectionValidatesProviderSupportAndPreservesRetryChoice() {
        assertThat(catalog.resolve("local-test","","PLAN").reasoningEffort()).isEqualTo("medium");
        assertThat(catalog.resolve("other-test","","PLAN").reasoningEffort()).isEmpty();
        assertThatThrownBy(() -> catalog.resolve("other-test","","PLAN","high"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("思考强度");
        assertThatThrownBy(() -> catalog.resolve("local-test","","PLAN","invalid"))
                .isInstanceOf(IllegalArgumentException.class);

        String novel=projects.create("密信","追查密信","保持人物记忆",1,BigDecimal.TEN,BigDecimal.ONE,"local-test","text-model","high");
        var task=store.one("SELECT * FROM tasks WHERE novel_id=?",novel);
        assertThat(store.novel(novel).get("reasoning_effort")).isEqualTo("high");
        assertThat(catalog.forTask(task).reasoningEffort()).isEqualTo("high");
        projects.pause(novel);
        projects.selectModel(novel,"local-test","text-model","low");
        assertThat(catalog.forTask(task).reasoningEffort()).isEqualTo("low");

        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",task.get("id"));
        store.update("UPDATE novels SET status='ERROR' WHERE id=?",novel);
        String retried=projects.retry(Store.string(task,"id"),"local-test","custom-model","medium");
        var replacement=store.one("SELECT * FROM tasks WHERE id=?",retried);
        assertThat(replacement.get("reasoning_effort")).isEqualTo("medium");
        assertThat(store.novel(novel).get("reasoning_effort")).isEqualTo("low");
        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",retried);
        String again=projects.retry(retried);
        assertThat(catalog.forTask(store.one("SELECT * FROM tasks WHERE id=?",again)).reasoningEffort()).isEqualTo("medium");
        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",again);
        String effortOnly=projects.retry(again,null,null,"high");
        var effortChoice=catalog.forTask(store.one("SELECT * FROM tasks WHERE id=?",effortOnly));
        assertThat(effortChoice.model()).isEqualTo("custom-model");
        assertThat(effortChoice.reasoningEffort()).isEqualTo("high");
    }

    @Test void researchDiscussionAndRetryPreserveReasoningChoice() {
        String first=research.create(List.of(),"调查信件","local-test","text-model","high");
        assertThat(catalog.forTask(store.one("SELECT * FROM tasks WHERE id=?",first)).reasoningEffort()).isEqualTo("high");
        String report=UUID.randomUUID().toString();
        store.update("UPDATE tasks SET status='DONE',research_id=? WHERE id=?",report,first);
        store.update("INSERT INTO research(id,task_id,sources,ideas,brief) VALUES(?,?, '[]','[]','调查信件')",report,first);
        assertThat(research.detail(report).get("modelChoice")).isEqualTo(Map.of("provider","local-test","model","text-model","reasoningEffort","high"));
        String chat=research.chat(report,"继续讨论");
        assertThat(catalog.forTask(store.one("SELECT * FROM tasks WHERE id=?",chat)).reasoningEffort()).isEqualTo("high");
        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",chat);
        String retry=projects.retry(chat,"local-test","text-model","low");
        assertThat(catalog.forTask(store.one("SELECT * FROM tasks WHERE id=?",retry)).reasoningEffort()).isEqualTo("low");
    }
    @Test void novelSelectionRequiresIdleStateAndKeepsPriorCall() {
        String id=projects.create("密信","追查密信","保持人物记忆",1,new BigDecimal("100"),new BigDecimal("5"));
        var task=store.one("SELECT * FROM tasks WHERE novel_id=?",id);
        budget.reserve(task,BigDecimal.ONE,"paid-model",BigDecimal.ONE,BigDecimal.ONE);
        assertThatThrownBy(()->projects.selectModel(id,"local-test","another-model")).isInstanceOf(IllegalStateException.class);
        projects.pause(id);
        store.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task.get("id"));
        assertThatThrownBy(()->projects.selectModel(id,"local-test","another-model")).hasMessageContaining("等待");
        store.update("UPDATE tasks SET status='WAITING' WHERE id=?",task.get("id"));
        projects.selectModel(id,"local-test","another-model");
        assertThat(catalog.forTask(task).model()).isEqualTo("another-model");
        var call=store.one("SELECT * FROM model_calls WHERE task_id=?",task.get("id"));
        assertThat(call.get("model")).isEqualTo("paid-model");
        assertThat(call.get("billing_mode")).isEqualTo("metered");
        assertThat(budget.novelUsed(id)).isEqualByComparingTo("1");
    }
    @Test void freeCallsRemainAvailableAfterPaidBudgetIsConsumed() {
        String first=store.enqueue(null,"RESEARCH",0,0,0,"paid",Map.of());
        budget.reserve(store.one("SELECT * FROM tasks WHERE id=?",first),new BigDecimal("300"),"paid-model",BigDecimal.ONE,BigDecimal.ONE);
        String second=store.enqueue(null,"RESEARCH",0,0,0,"free",Map.of());
        budget.reserve(store.one("SELECT * FROM tasks WHERE id=?",second),BigDecimal.ZERO,"text-model",BigDecimal.ZERO,BigDecimal.ZERO,null,null,"local-test","unmetered");
        assertThat(budget.monthlyUsed(budget.month())).isEqualByComparingTo("300");
        assertThat(store.one("SELECT billing_mode FROM model_calls WHERE task_id=?",second).get("billing_mode")).isEqualTo("unmetered");
        store.update("UPDATE model_calls SET status='UNCERTAIN' WHERE task_id=?",second);
        String call=(String)store.one("SELECT id FROM model_calls WHERE task_id=?",second).get("id");
        assertThatThrownBy(()->budget.reconcile(call,BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        budget.reconcile(call,BigDecimal.ZERO);
    }
    @Test void researchPersistsExplicitChoiceAndRetryRetainsIt() {
        String id=research.create(List.of(),"职业悬疑","other-test","chosen-research-model");
        var task=store.one("SELECT * FROM tasks WHERE id=?",id);
        assertThat(catalog.forTask(task).provider()).isEqualTo("other-test");
        assertThat(catalog.forTask(task).model()).isEqualTo("chosen-research-model");
        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",id);
        String retried=projects.retry(id);
        var next=store.one("SELECT * FROM tasks WHERE id=?",retried);
        assertThat(next.get("provider")).isEqualTo("other-test");
        assertThat(next.get("model_name")).isEqualTo("chosen-research-model");
    }
    @Test void novelRetryUsesTaskChoiceAndRetainsOriginalModelAndCall() {
        String novel=projects.create("密信","追查密信","保持人物记忆",1,BigDecimal.TEN,BigDecimal.ONE);
        var original=store.one("SELECT * FROM tasks WHERE novel_id=?",novel);
        String taskId=Store.string(original,"id");
        budget.reserve(original,BigDecimal.ZERO,"text-model",BigDecimal.ZERO,BigDecimal.ZERO,null,null,"local-test","unmetered");
        store.update("UPDATE model_calls SET status='UNCERTAIN' WHERE task_id=?",taskId);
        store.update("UPDATE tasks SET status='ERROR',error='连接失败' WHERE id=?",taskId);
        assertThatThrownBy(()->projects.retry(taskId,"other-test","replacement-model")).hasMessageContaining("核对");
        assertThat(store.one("SELECT status FROM tasks WHERE id=?",taskId).get("status")).isEqualTo("ERROR");
        String callId=Store.string(store.one("SELECT id FROM model_calls WHERE task_id=?",taskId),"id");
        budget.reconcile(callId,BigDecimal.ZERO);
        String nextId=projects.retry(taskId,"other-test","replacement-model");
        var next=store.one("SELECT * FROM tasks WHERE id=?",nextId);
        assertThat(next.get("provider")).isEqualTo("other-test");
        assertThat(next.get("model_name")).isEqualTo("replacement-model");
        assertThat(catalog.forTask(next).model()).isEqualTo("replacement-model");
        assertThat(catalog.forTask(next).provider()).isEqualTo("other-test");
        assertThat(next.get("payload")).isEqualTo(original.get("payload"));
        assertThat(store.one("SELECT provider FROM novels WHERE id=?",novel).get("provider")).isEqualTo("local-test");
        assertThat(store.one("SELECT status FROM tasks WHERE id=?",taskId).get("status")).isEqualTo("CANCELLED");
        var call=store.one("SELECT * FROM model_calls WHERE id=?",callId);
        assertThat(call.get("model")).isEqualTo("text-model");
        assertThat(call.get("status")).isEqualTo("RECONCILED");
        assertThat((BigDecimal)call.get("actual")).isZero();
        assertThat(store.list("SELECT * FROM model_calls WHERE task_id=?",nextId)).isEmpty();
    }
    @Test void invalidRetryChoiceKeepsFailedTaskAvailable() {
        String task=research.create(List.of(),"职业悬疑","local-test","text-model");
        store.update("UPDATE tasks SET status='ERROR' WHERE id=?",task);
        assertThatThrownBy(()->projects.retry(task,"missing","replacement-model")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->projects.retry(task,"default","replacement-model")).hasMessageContaining("配置");
        assertThat(store.one("SELECT status FROM tasks WHERE id=?",task).get("status")).isEqualTo("ERROR");
        assertThat(store.list("SELECT * FROM tasks")).hasSize(1);
    }
    @Test void discussionInheritsReportChoiceAndSupportsPerMessageSwitch() {
        String original=research.create(List.of(),"职业悬疑","other-test","chosen-research-model");
        store.update("UPDATE tasks SET status='DONE' WHERE id=?",original);
        String report=UUID.randomUUID().toString();
        store.update("INSERT INTO research(id,task_id,sources,ideas,brief) VALUES(?,?,'[]','[]','职业悬疑')",report,original);
        String inherited=research.chat(report,"扩大世界范围");
        var first=store.one("SELECT * FROM tasks WHERE id=?",inherited);
        assertThat(catalog.forTask(first).provider()).isEqualTo("other-test");
        assertThat(catalog.forTask(first).model()).isEqualTo("chosen-research-model");
        store.update("UPDATE tasks SET status='DONE' WHERE id=?",inherited);
        String switched=research.chat(report,"补充职业细节","local-test","discussion-model");
        var second=store.one("SELECT * FROM tasks WHERE id=?",switched);
        assertThat(catalog.forTask(second).provider()).isEqualTo("local-test");
        assertThat(catalog.forTask(second).model()).isEqualTo("discussion-model");
        assertThat(json.write(research.detail(report).get("modelChoice"))).contains("discussion-model","local-test");
        assertThat(store.one("SELECT model_name FROM tasks WHERE id=?",original).get("model_name")).isEqualTo("chosen-research-model");
    }
    @Test void invalidResearchProviderIsRejectedBeforeCreatingTask() {
        assertThatThrownBy(()->research.create(List.of(),"职业悬疑","missing","")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->research.create(List.of(),"职业悬疑","default","")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.list("SELECT * FROM tasks")).isEmpty();
    }
}
