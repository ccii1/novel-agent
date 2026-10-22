package dev.novel;

import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.support.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_DELEGATION_LIVE",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/multi-agent-qa;DB_CLOSE_ON_EXIT=FALSE","novel.engine-enabled=false","novel.redis.enabled=false"})
class LiveAgentDelegationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired CoordinationService coordination;
    @Autowired AgentService agents;
    @Autowired WorkflowService workflow;
    @Autowired ModelCatalog catalog;
    @Autowired Json json;
    @Test @Timeout(600) void realCoordinatorUsesToolAndDelegatesDurableOutlineTask() throws Exception {
        String provider=System.getenv().getOrDefault("NOVEL_DELEGATION_PROVIDER","local");
        String model=System.getenv().getOrDefault("NOVEL_DELEGATION_MODEL","gpt-5.6-terra");
        assertThat(catalog.resolve(provider,model,"COORDINATE").ready()).isTrue();
        var recorded=json.read(Files.readString(Path.of(".work/combined-review-case.json")));
        var plan=recorded.path("plan");int chapter=recorded.path("chapter").asInt();
        String id=projects.create("多 Agent 委派验证",recorded.path("premise").asText(),recorded.path("bible").asText(),plan.path("chapters").size(),new BigDecimal("2"),new BigDecimal("2"),provider,model);
        store.update("tasks.cancelNovel",id);
        db.update("UPDATE novels SET status='AWAITING_CHANGE',chapter_no=?,plan=? WHERE id=?",chapter,json.write(plan),id);
        store.update("chapters.draft",id,chapter,1,0,recorded.path("title").asText(),recorded.path("body").asText(),0);
        String taskId=coordination.request(id,"仅调整第3章的伏笔安排，延迟揭示封条来源，保留已有伏笔。正文与记忆保持当前内容，请交给局部大纲修改 Agent。");
        execute(taskId);
        assertThat(store.list("planChanges.list",id)).isEmpty();
        var turns=store.list("modelTurns.list",taskId);
        assertThat(turns.size()).isBetween(2,3);
        assertThat(turns).allSatisfy(turn->assertThat(turn.get("status")).isEqualTo("COMPLETE"));
        assertThat(db.list("SELECT * FROM events WHERE task_id=? AND type='AGENT_TOOL_USED'",taskId)).isNotEmpty();
        var call=db.one("SELECT * FROM model_calls WHERE task_id=?",taskId);
        assertThat(((Number)call.get("input_tokens")).longValue()).isEqualTo(turns.stream().mapToLong(turn->Store.number(turn,"input_tokens")).sum());
        assertThat(((Number)call.get("output_tokens")).longValue()).isEqualTo(turns.stream().mapToLong(turn->Store.number(turn,"output_tokens")).sum());
        assertThat((BigDecimal)call.get("actual")).isBetween(BigDecimal.ZERO,new BigDecimal("2"));
        String next=coordination.approve(id,taskId);
        assertThat(store.one("tasks.find",next)).containsEntry("kind","PLAN_CHANGE").containsEntry("status","WAITING");
        assertThatThrownBy(()->coordination.approve(id,taskId)).isInstanceOf(IllegalStateException.class);
        execute(next);
        assertThat(store.list("planChanges.list",id)).hasSize(1);
        assertThat(store.list("planChanges.list",id).get(0).get("status")).isEqualTo("PENDING");
        assertThat(json.read(Store.string(store.novel(id),"plan"))).isEqualTo(plan);
        assertThat(store.one("chapters.find",id,chapter).get("body")).isEqualTo(recorded.path("body").asText());
        long before=db.list("SELECT * FROM model_call_turns WHERE task_id=?",taskId).size();
        agents.execute(store.one("tasks.find",taskId));
        assertThat(db.list("SELECT * FROM model_call_turns WHERE task_id=?",taskId)).hasSize((int)before);
        Files.writeString(Path.of(".work/multi-agent-live-result.json"),json.write(Map.of("id",id,"coordinatorTask",taskId,"delegateTask",next,"coordinatorTurns",turns.size(),"calls",db.list("SELECT task_id,input_tokens,output_tokens,actual FROM model_calls WHERE novel_id=?",id))));
    }
    private void execute(String taskId) throws Exception {
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",taskId);
        var task=store.one("tasks.find",taskId);
        workflow.finished(task,agents.execute(task));
    }
}
