package dev.novel;

import dev.novel.integration.model.ModelCatalog;
import dev.novel.repository.Store;
import dev.novel.service.AgentService;
import dev.novel.service.ProjectService;
import dev.novel.service.WorkflowService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_MEMORY_REPAIR_LIVE",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-repair-live;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class LiveMemoryRepairTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired AgentService agents;
    @Autowired ModelCatalog catalog;
    @Autowired Json json;
    @Test @Timeout(900) void realMemoryRepairAndReviewPreserveRecordedChapterAndPlan() throws Exception {
        assertThat(catalog.resolve("local","gpt-5.6-terra","MEMORY_REPAIR").billingMode()).isEqualTo("unmetered");
        assertThat(catalog.resolve("local","gpt-5.6-terra","MEMORY_REPAIR").ready()).isTrue();
        var recorded=json.read(Files.readString(Path.of(".work/combined-review-case.json")));
        String id=projects.create("真实记忆修正验证",recorded.path("premise").asText(),recorded.path("bible").asText(),10,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-5.6-terra");
        int chapter=recorded.path("chapter").asInt();
        db.update("UPDATE novels SET chapter_no=?,status='CHECKING_MEMORY',plan=? WHERE id=?",chapter,json.write(recorded.path("plan")),id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,?,1,0,?,?,'DRAFT',0)",id,chapter,recorded.path("title").asText(),recorded.path("body").asText());
        String original=store.enqueue(id,"CHECK_MEMORY",chapter,0,0,"memory-live:"+UUID.randomUUID(),recorded.path("delta"));
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",original);
        workflow.finished(store.one("tasks.find",original),recorded.path("review"));
        for(int attempt=0;attempt<WorkflowService.MEMORY_REPAIR_LIMIT && "EXTRACTING".equals(store.novel(id).get("status"));attempt++) {
            execute(id,"MEMORY_REPAIR");execute(id,"CHECK_MEMORY");
        }
        var novel=store.novel(id);
        assertThat(novel.get("status")).isIn("WRITING","AWAITING_STAGE_REVIEW","AWAITING_HELP");
        assertThat(store.one("chapters.find",id,chapter)).containsEntry("body",recorded.path("body").asText()).containsEntry("revision",0);
        assertThat(json.read(Store.string(novel,"plan"))).isEqualTo(recorded.path("plan"));
        var calls=db.list("SELECT t.kind,c.input_tokens,c.output_tokens,c.actual,c.latency_ms FROM model_calls c JOIN tasks t ON t.id=c.task_id WHERE c.novel_id=?",id);
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(call->assertThat((BigDecimal)call.get("actual")).isEqualByComparingTo(BigDecimal.ZERO));
        Files.writeString(Path.of(".work/memory-repair-live-result.json"),json.write(Map.of("status",novel.get("status"),"memoryVersion",novel.get("memory_version"),
                "calls",calls,"tasks",db.list("SELECT kind,status,payload,result FROM tasks WHERE novel_id=? AND kind IN ('MEMORY_REPAIR','CHECK_MEMORY')",id))));
    }
    private void execute(String novel,String kind) throws Exception {
        var task=db.one("SELECT * FROM tasks WHERE novel_id=? AND kind=? AND status='WAITING'",novel,kind);
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task.get("id"));
        try {
            workflow.finished(store.one("tasks.find",task.get("id")),agents.execute(store.one("tasks.find",task.get("id"))));
        } finally {
            Files.writeString(Path.of(".work/memory-repair-live-calls.json"),json.write(db.list("SELECT t.kind,c.response,c.latency_ms FROM model_calls c JOIN tasks t ON t.id=c.task_id WHERE c.novel_id=?",novel)));
        }
    }
}
