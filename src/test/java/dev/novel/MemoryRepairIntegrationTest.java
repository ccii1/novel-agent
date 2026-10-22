package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.MemoryService;
import dev.novel.service.ProjectService;
import dev.novel.service.WorkflowService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import dev.novel.agent.ReviewValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:memory-repair;DB_CLOSE_DELAY=-1","novel.engine-enabled=false","novel.review-mode=combined"})
class MemoryRepairIntegrationTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired MemoryService memory;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired Json json;
    @Autowired ReviewValidator reviews;
    @LocalServerPort int port;
    private static final String BODY="陆禾判断这些出口条件是在防人跑。哨声在背后拉长。";
    private JsonNode delta() {
        return json.read("""
                {"summary":"陆禾判断这些条件被人为设置。","facts":[{"key":"exit-rule","kind":"WORLD","name":"出城条件","detail":"人物怀疑出口规则阻止人逃跑。","evidence":"这些出口条件是在防人跑"}],"relations":[]}
                """);
    }
    private JsonNode rejected(boolean major) {
        return json.tree(Map.of("approved",false,"majorChange",major,"issues",java.util.List.of(Map.of("severity","minor","quote","这些出口条件是在防人跑","reason","记忆将推测写成事实","suggestion","保留人物判断的确定程度"))));
    }
    private String novel() {
        String id=projects.create("记忆修正验证","出城规则调查","人物推测保持原有确定程度",1,BigDecimal.TEN,BigDecimal.ONE);
        db.update("UPDATE novels SET status='CHECKING_MEMORY',plan=? WHERE id=?","{\"chapters\":[{\"number\":1}]}",id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,1,1,2,'出口',?,'DRAFT',0)",id,BODY);
        return id;
    }
    private Map<String,Object> check(String novel,int attempt) {
        String id=store.enqueue(novel,"CHECK_MEMORY",1,2,0,UUID.randomUUID().toString(),Map.of("delta",delta(),"repairAttempt",attempt));
        assertThat(dispatch.claim(id)).isEqualTo(1);
        return store.one("tasks.find",id);
    }
    @Test void rejectionSchedulesOnlyMemoryRepairAndStopsAtThreeAttempts() {
        String id=novel();
        var first=check(id,0);workflow.finished(first,rejected(false));workflow.finished(first,rejected(false));
        assertThat(store.novel(id).get("status")).isEqualTo("EXTRACTING");
        assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='MEMORY_REPAIR'",id)).hasSize(1);
        assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='REWRITE'",id)).isEmpty();
        assertThat(store.one("chapters.find",id,1).get("body")).isEqualTo(BODY);
        for(int attempt=1;attempt<=3;attempt++) {
            var repair=db.one("SELECT * FROM tasks WHERE novel_id=? AND kind='MEMORY_REPAIR' AND status='WAITING'",id);
            dispatch.claim((String)repair.get("id"));
            workflow.finished(store.one("tasks.find",repair.get("id")),delta());
            var next=db.one("SELECT * FROM tasks WHERE novel_id=? AND kind='CHECK_MEMORY' AND status='WAITING'",id);
            assertThat(json.read((String)next.get("payload")).path("repairAttempt").asInt()).isEqualTo(attempt);
            dispatch.claim((String)next.get("id"));workflow.finished(store.one("tasks.find",next.get("id")),rejected(false));
        }
        assertThat(store.novel(id).get("status")).isEqualTo("AWAITING_HELP");
        assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='MEMORY_REPAIR'",id)).hasSize(3);
        assertThat(memory.snapshot(id,0)).isEmpty();
        assertThat(projects.memoryReview(id)).containsEntry("pending",true).containsEntry("repairAttempt",3);
    }
    @Test void manualEditUsesRealHttpAndAcceptsMemoryWithoutChangingBodyOrPlan() throws Exception {
        String id=novel();var task=check(id,3);workflow.finished(task,rejected(false));
        var original=store.novel(id);
        var edited=delta().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)edited).put("summary","陆禾判断出口条件是在防止人逃跑。");
        var response=AuthHttp.owner(port).request("/api/novels/"+id+"/memory-repair",json.write(Map.of("taskId",task.get("id"),"instructions","按意见修改摘要","delta",edited)));
        assertThat(response.statusCode()).isEqualTo(200);
        String next=json.read(response.body()).path("id").asText();
        assertThat(store.one("tasks.find",next).get("kind")).isEqualTo("CHECK_MEMORY");
        assertThat(projects.memoryReview(id)).containsEntry("pending",false);
        assertThatThrownBy(()->projects.repairMemory(id,(String)task.get("id"),"重复提交",edited)).hasMessageContaining("已经变化");
        dispatch.claim(next);workflow.finished(store.one("tasks.find",next),json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[]}"));
        assertThat(store.novel(id)).containsEntry("status","AWAITING_STAGE_REVIEW").containsEntry("plan",original.get("plan"));
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(1);
        assertThat(store.one("chapters.find",id,1)).containsEntry("body",BODY).containsEntry("revision",2).containsEntry("status","ACCEPTED");
        assertThat(memory.snapshot(id,1)).hasSize(1);
    }
    @Test void majorChangesWaitForHumanAndGeneralRewriteEndpointRejectsMemoryProblems() {
        String id=novel();var task=check(id,0);workflow.finished(task,rejected(true));
        assertThat(store.novel(id).get("status")).isEqualTo("AWAITING_HELP");
        assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='MEMORY_REPAIR'",id)).isEmpty();
        assertThatThrownBy(()->projects.revise(id,"修改摘要",null)).hasMessageContaining("记忆修正入口");
        assertThatThrownBy(()->projects.repairMemory(id,"stale-task","修改摘要",null)).hasMessageContaining("已经变化");
        String repair=projects.repairMemory(id,(String)task.get("id"),"保留人物推测",null);
        assertThat(store.one("tasks.find",repair)).containsEntry("kind","MEMORY_REPAIR").containsEntry("revision",2);
    }
    @Test void combinedReviewSchedulingIsIdempotent() {
        String id=novel();var novel=store.novel(id);
        workflow.ensureReviewTasks(novel,1,2);workflow.ensureReviewTasks(novel,1,2);
        assertThat(db.list("SELECT kind FROM tasks WHERE novel_id=? AND kind LIKE 'CHECK_%'",id))
                .extracting(row->row.get("kind")).containsExactly("CHECK_CHAPTER");
    }
    @Test void staleMemoryReviewCannotCommitOrAdvanceChapter() {
        String id=novel();var task=check(id,0);
        db.update("UPDATE chapters SET revision=3 WHERE novel_id=?",id);
        assertThatThrownBy(()->workflow.finished(task,json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[]}"))).hasMessageContaining("版本已经变化");
        assertThat(Store.number(store.novel(id),"memory_version")).isZero();
        assertThat(memory.snapshot(id,0)).isEmpty();
    }
    @Test
    @EnabledIfEnvironmentVariable(named="NOVEL_MEMORY_OBJECT_REPLAY_FILE",matches=".+")
    void recordedObjectReferenceContinuesIntoMemoryRepair() throws Exception {
        var saved=json.read(Files.readString(Path.of(System.getenv("NOVEL_MEMORY_OBJECT_REPLAY_FILE"))));
        String id=novel();var task=check(id,0);
        db.update("UPDATE chapters SET body=? WHERE novel_id=?",saved.path("body").asText(),id);
        store.update("tasks.payload",json.write(Map.of("delta",saved.path("delta"),"repairAttempt",0)),task.get("id"));
        var result=reviews.validate(saved.path("output"),saved.path("body").asText(),saved.path("delta"));
        workflow.finished(store.one("tasks.find",task.get("id")),result);
        assertThat(store.novel(id).get("status")).isEqualTo("EXTRACTING");
        var next=db.one("SELECT * FROM tasks WHERE novel_id=? AND kind='MEMORY_REPAIR'",id);
        assertThat(json.read((String)next.get("payload")).path("review").path("issues").get(0).path("quoteValue"))
                .isEqualTo(saved.path("delta").path("facts").get(0));
        assertThat(store.one("chapters.find",id,1).get("body")).isEqualTo(saved.path("body").asText());
    }
}
