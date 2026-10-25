package dev.novel;

import dev.novel.agent.AgentContextService;
import dev.novel.agent.runtime.StoryReadTools;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.*;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:core-writing;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class CoreWorkflowIntegrationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired AgentService agents;
    @Autowired MemoryService memory;
    @Autowired AgentContextService context;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired Json json;
    @LocalServerPort int port;
    private String novel() {
        String id=projects.create("铜钥匙","调查城门","保持知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","核对铜钥匙","coreMilestones",List.of("进入城门"),
                "chapters",List.of(Map.of("number",1,"title","城门","goal","进入城门","characters",List.of("林舟"),"foreshadowIds",List.of())))),id);
        projects.approvePlan(id,null);store.update("tasks.cancelNovel",id);
        store.update("chapters.draft",id,1,1,0,"城门","林舟接过铜钥匙。\n\n他走向城门。",0);
        return id;
    }
    @Test void fourComponentsExposeOnlyCoreTools() {
        assertThat(agents.catalogue()).hasSize(4);
        assertThat(agents.catalogue()).extracting(row->row.get("agent"))
                .containsExactlyInAnyOrder("PlannerAgent","WriterAgent","ChapterReviewAgent","MemoryExtractionAgent");
        assertThat(agents.runtime("WRITE").tools()).contains("read_memory","search_memory","read_source")
                .doesNotContain("delegate_draft","read_relations","read_candidate_memory","lookup_memory");
        assertThatThrownBy(()->agents.runtime("COORDINATE")).hasMessageContaining("未知");
    }
    @Test void oneReviewAdvancesToMemoryWithoutAdditionalReviewNodes() {
        String id=novel();db.update("UPDATE novels SET status='CHECKING' WHERE id=?",id);
        workflow.ensureReviewTasks(store.novel(id),1,0);workflow.ensureReviewTasks(store.novel(id),1,0);
        var checks=db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='CHECK_CHAPTER'",id);
        assertThat(checks).hasSize(1);
        var task=checks.get(0);dispatch.claim(Store.string(task,"id"));
        workflow.finished(store.one("tasks.find",task.get("id")),json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[],\"narrative\":{}}"));
        assertThat(store.novel(id)).containsEntry("status","EXTRACTING");
        assertThat(db.list("SELECT kind FROM tasks WHERE novel_id=? AND status='WAITING'",id))
                .extracting(row->row.get("kind")).containsExactly("MEMORY");
    }
    @Test void ordinaryProblemsRewriteAndMajorChangesWaitForApproval() {
        for(boolean major:List.of(false,true)) {
            String id=novel();db.update("UPDATE novels SET status='CHECKING' WHERE id=?",id);
            workflow.ensureReviewTasks(store.novel(id),1,0);
            var task=db.one("SELECT * FROM tasks WHERE novel_id=? AND kind='CHECK_CHAPTER'",id);
            dispatch.claim(Store.string(task,"id"));
            workflow.finished(store.one("tasks.find",task.get("id")),json.tree(Map.of("approved",false,"majorChange",major,
                    "issues",List.of(Map.of("reason","需要补充行动依据")),"narrative",Map.of())));
            assertThat(store.novel(id).get("status")).isEqualTo(major?"AWAITING_CHANGE":"WRITING");
            assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='REFLECT'",id)).isEmpty();
            assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind='REWRITE'",id)).hasSize(major?0:1);
        }
    }
    @Test void fileAndParagraphToolsReadRealDataAndRejectStaleVersions() {
        String id=novel();String task=store.enqueue(id,"CHECK_CHAPTER",1,0,0,UUID.randomUUID().toString(),Map.of());
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        assertThat(json.read(tools.paragraph("p1")).path("text").asText()).isEqualTo("他走向城门。");
        assertThat(json.read(tools.file("current_state.md",0,6000)).path("status").asText()).isEqualTo("FOUND");
        assertThat(json.read(tools.batch(List.of("current_state.md","characters/missing.md"),0,6000)).path("missingPaths"))
                .containsExactly(json.tree("characters/missing.md"));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        assertThatThrownBy(()->tools.paragraph("p0")).hasMessageContaining("记忆版本已变化");
    }
    @Test void removedHistoricalTaskCannotBeRetried() {
        String id=novel();String task=store.enqueue(id,"COORDINATE",1,0,0,UUID.randomUUID().toString(),Map.of());
        db.update("UPDATE tasks SET status='ERROR' WHERE id=?",task);
        assertThatThrownBy(()->projects.retry(task)).hasMessageContaining("功能已移除");
        assertThat(store.one("tasks.find",task)).containsEntry("status","ERROR");
    }
    @Test void httpCreatesApprovesAndPausesWithoutCallingModel() throws Exception {
        var http=AuthHttp.owner(port);
        var response=http.request("/api/novels",json.write(Map.of("title","接口创作验证","premise","调查城门","bible","保持人物知情",
                "stageSize",1,"novelBudget",10,"chapterBudget",1)));
        assertThat(response.statusCode()).isEqualTo(200);
        String id=json.read(response.body()).path("id").asText();
        store.update("tasks.cancelNovel",id);
        store.update("novels.awaitPlan",Store.string(store.novel(novel()),"plan"),id);
        var approved=(com.fasterxml.jackson.databind.node.ObjectNode)json.read(Store.string(store.novel(id),"plan"));
        approved.put("stageGoal","人工确认后的阶段目标");
        assertThat(http.request("/api/novels/"+id+"/approve-plan",json.write(approved)).statusCode()).isEqualTo(200);
        assertThat(json.read(Store.string(store.novel(id),"plan")).path("stageGoal").asText()).isEqualTo("人工确认后的阶段目标");
        assertThat(http.request("/api/novels/"+id+"/pause","{}").statusCode()).isEqualTo(200);
        assertThat(store.novel(id)).containsEntry("status","PAUSED");
        assertThat(db.list("SELECT * FROM model_calls WHERE novel_id=?",id)).isEmpty();
        for(String path:List.of("/api/research","/api/novels/"+id+"/coordination","/api/novels/"+id+"/plan-changes","/api/novels/"+id+"/graph"))
            assertThat(http.request(path,null).statusCode()).as(path).isEqualTo(404);
    }
    @Test void registeredUserCannotReadAnotherOwnersNovel() throws Exception {
        String id=novel();var http=new AuthHttp(port);String username="reader_"+UUID.randomUUID().toString().substring(0,8);
        assertThat(http.request("/api/auth/register",json.write(Map.of("username",username,"password","testpass"))).statusCode()).isEqualTo(200);
        http.login(username,"testpass");
        assertThat(http.request("/api/novels/"+id,null).statusCode()).isEqualTo(404);
    }
}
