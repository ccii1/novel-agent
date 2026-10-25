package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.*;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:memory-patches;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class MemoryPatchIntegrationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired MemoryService memory;
    @Autowired MemoryPatchService patches;
    @Autowired WorkflowService workflow;
    @Autowired AgentService agents;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired MemoryMarkdown markdown;
    @Autowired Json json;
    @Autowired dev.novel.agent.AgentContextService context;
    private static final String BODY="陈岚接过铜钥匙，收进衣袋。\n\n两人进入砖道。";
    private String novel() {
        String id=projects.create("记忆局部修改验证","调查铜钥匙","人物判断需要来源",1,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        var initial=json.tree(Map.of("summary","钥匙由林舟保管，账册仍在井下。","files",List.of(
                file(id,0,"characters/lin.md",markdown.create("lin","character","林舟",List.of(),
                        "## 物品\n\n铜钥匙由林舟保管。\n\n完整账册仍在井下木匣，尚未取回。\n\n## 线索\n\n木板有灰。木板有灰。")),
                file(id,0,"current_state.md",markdown.create("current-state","state","当前状态",List.of(),"两人在井口。")),
                file(id,0,"handoff_current.md",markdown.create("handoff","handoff","章节交接",List.of(),"继续调查铜钥匙。")))));
        memory.commit(id,1,1,memory.validateDelta(initial,BODY));
        db.update("UPDATE novels SET status='EXTRACTING',memory_version=1,chapter_no=2,plan=? WHERE id=?",
                "{\"chapters\":[{\"number\":2}]}",id);
        store.update("chapters.draft",id,2,1,0,"砖道",BODY,1);
        return id;
    }
    private Map<String,Object> file(String id,long version,String path,String content) {
        return Map.of("path",path,"expectedHash",memory.files().hash(id,version,path),"content",content,
                "description","记录当前物品与位置。","evidencePaths",List.of("/paragraphs/p0"));
    }
    private Map<String,Object> edit(String id,String path,String oldText,String newText) {
        return Map.of("path",path,"expectedHash",memory.files().hash(id,1,path),
                "edits",List.of(Map.of("oldText",oldText,"newText",newText)),
                "description","更新本章物品交付与位置。","evidencePaths",List.of("/paragraphs/p0","/paragraphs/p1"));
    }
    private JsonNode proposed(String id) {
        return json.tree(Map.of("summary","陈岚接过钥匙，两人进入砖道。","patches",List.of(
                edit(id,"characters/lin.md","铜钥匙由林舟保管。","铜钥匙已交给陈岚。来源：第2章 p0。"),
                edit(id,"current_state.md","两人在井口。","两人进入砖道。来源：第2章 p1。"),
                edit(id,"handoff_current.md","继续调查铜钥匙。","陈岚保管铜钥匙，继续调查砖道。来源：第2章 p0、p1。"))));
    }
    @Test void partialUpdatePreservesUnchangedFactsAndCommitsWithoutReviewTasks() {
        String id=novel();var delta=patches.materialize(id,1,proposed(id),BODY);
        assertThat(delta.path("files").get(0).path("content").asText())
                .contains("铜钥匙已交给陈岚。","完整账册仍在井下木匣，尚未取回。","木板有灰。木板有灰。");
        String task=store.enqueue(id,"MEMORY",2,0,1,UUID.randomUUID().toString(),Map.of());
        dispatch.claim(task);workflow.finished(store.one("tasks.find",task),delta);
        assertThat(store.novel(id)).containsEntry("status","AWAITING_STAGE_REVIEW");
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(2);
        assertThat(store.one("chapters.find",id,2)).containsEntry("status","ACCEPTED");
        assertThat(db.list("SELECT * FROM tasks WHERE novel_id=? AND kind IN ('CHECK_MEMORY','MEMORY_REPAIR')",id)).isEmpty();
        assertThat(memory.files().readFull(id,1,"characters/lin.md")).contains("铜钥匙由林舟保管。");
        assertThat(memory.files().readFull(id,2,"characters/lin.md")).contains("完整账册仍在井下木匣，尚未取回。");
    }
    @Test void extractionContextLoadsEntryFilesWithoutPreloadingEntityArchives() {
        String id=novel();
        String task=store.enqueue(id,"MEMORY",2,0,1,UUID.randomUUID().toString(),Map.of());
        var prompt=json.read(context.context(store.one("tasks.find",task),List.of("current_state.md","handoff_current.md")));
        assertThat(prompt.has("requiredTopics")).isFalse();
        assertThat(prompt.has("story_bible.md")).isFalse();
        assertThat(prompt.has("outline.md")).isFalse();
        assertThat(prompt.has("current_state.md")).isTrue();
        assertThat(prompt.has("handoff_current.md")).isTrue();
        assertThat(prompt.toString()).doesNotContain("完整账册仍在井下木匣");
    }
    @Test void staleHashAndAmbiguousOriginalAreRejectedWithoutPublishing() {
        String id=novel();var stale=proposed(id);
        ((ObjectNode)stale.path("patches").get(0)).put("expectedHash","incorrect");
        assertThatThrownBy(()->patches.materialize(id,1,stale,BODY)).hasMessageContaining("版本不匹配");
        var ambiguous=proposed(id);
        ((ObjectNode)ambiguous.path("patches").get(0).path("edits").get(0)).put("oldText","木板有灰。");
        assertThatThrownBy(()->patches.materialize(id,1,ambiguous,BODY)).hasMessageContaining("唯一匹配");
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(1);
    }
    @Test void overlapAndWholeTopicReplacementAreRejected() {
        String id=novel();var overlap=proposed(id);
        ((com.fasterxml.jackson.databind.node.ArrayNode)overlap.path("patches").get(0).path("edits"))
                .add(json.tree(Map.of("oldText","钥匙由林舟保管","newText","已交付")));
        assertThatThrownBy(()->patches.materialize(id,1,overlap,BODY)).hasMessageContaining("范围重叠");
        var whole=proposed(id);
        ((ObjectNode)whole.path("patches").get(0).path("edits").get(0))
                .put("oldText",memory.files().readFull(id,1,"characters/lin.md"));
        assertThatThrownBy(()->patches.materialize(id,1,whole,BODY)).hasMessageContaining("局部原文");
    }
    @Test void evidenceAndStableIdentityAreStillValidated() {
        String id=novel();var invalid=proposed(id);
        ((ObjectNode)invalid.path("patches").get(0)).putArray("evidencePaths").add("/paragraphs/p999");
        assertThatThrownBy(()->patches.materialize(id,1,invalid,BODY)).hasMessageContaining("证据不存在");
        var identity=proposed(id);
        ((ObjectNode)identity.path("patches").get(0)).putArray("edits").add(json.tree(Map.of("oldText","id: \"lin\"","newText","id: \"changed\"")));
        assertThatThrownBy(()->patches.materialize(id,1,identity,BODY)).hasMessageContaining("稳定标识");
    }
    @Test void pendingLegacyValidationUsesNoModelCall() throws Exception {
        String id=novel();var delta=patches.materialize(id,1,proposed(id),BODY);
        String task=workflow.scheduleMemoryCheck(store.novel(id),Map.of("id",UUID.randomUUID().toString(),"chapter_no",2,"revision",0,"memory_version",1),delta,0);
        dispatch.claim(task);var running=store.one("tasks.find",task);
        workflow.finished(running,agents.execute(running));
        assertThat(db.list("SELECT * FROM model_calls WHERE task_id=?",task)).isEmpty();
        assertThat(store.one("chapters.find",id,2)).containsEntry("status","ACCEPTED");
    }
    @Test void manualApprovalOfHistoricalCandidatePreservesPlanAndRequiresCurrentTask() throws Exception {
        String id=novel();var delta=patches.materialize(id,1,proposed(id),BODY);
        String old=store.enqueue(id,"CHECK_MEMORY",2,0,1,UUID.randomUUID().toString(),Map.of("delta",delta,"repairAttempt",3));
        store.update("tasks.done",json.write(Map.of("approved",false,"majorChange",false,"issues",List.of())),old);
        db.update("UPDATE novels SET status='AWAITING_HELP' WHERE id=?",id);
        String plan=Store.string(store.novel(id),"plan");
        assertThat(projects.memoryReview(id)).containsEntry("pending",true);
        assertThatThrownBy(()->projects.repairMemory(id,"stale-task","采用人工修改",delta)).hasMessageContaining("已经变化");
        String next=projects.repairMemory(id,old,"采用人工修改",delta);
        dispatch.claim(next);var task=store.one("tasks.find",next);
        workflow.finished(task,agents.execute(task));
        assertThat(store.novel(id)).containsEntry("plan",plan).containsEntry("status","AWAITING_STAGE_REVIEW");
        assertThat(store.one("chapters.find",id,2)).containsEntry("body",BODY);
        assertThat(db.list("SELECT * FROM model_calls WHERE task_id=?",next)).isEmpty();
    }
    @Test void newThemeAndOutputBudgetAreValidatedBeforePublication() {
        String id=novel();var proposal=proposed(id);
        var added=(ObjectNode)((com.fasterxml.jackson.databind.node.ArrayNode)proposal.path("patches")).addObject();
        added.put("path","characters/chen.md").put("expectedHash","")
                .put("content",markdown.create("chen","character","陈岚",List.of(),"持有铜钥匙。来源：第2章 p0。"))
                .put("description","陈岚接收铜钥匙。");
        added.putArray("edits");added.putArray("evidencePaths").add("/paragraphs/p0");
        assertThat(patches.materialize(id,1,proposal,BODY).path("files")).hasSize(4);
        added.put("content","内容".repeat(12000));
        assertThatThrownBy(()->patches.materialize(id,1,proposal,BODY)).hasMessageContaining("32000字节");
        assertThat(memory.files().hash(id,1,"characters/chen.md")).isEmpty();
    }
    @Test void changedDraftCannotPublishAndPausedResultRemainsReady() {
        String id=novel();var delta=patches.materialize(id,1,proposed(id),BODY);
        String task=store.enqueue(id,"MEMORY",2,0,1,UUID.randomUUID().toString(),Map.of());
        dispatch.claim(task);db.update("UPDATE chapters SET revision=1 WHERE novel_id=? AND number=2",id);
        assertThatThrownBy(()->workflow.finished(store.one("tasks.find",task),delta)).hasMessageContaining("版本已经变化");
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(1);
        db.update("UPDATE chapters SET revision=0 WHERE novel_id=? AND number=2",id);
        db.update("UPDATE novels SET status='PAUSED' WHERE id=?",id);
        workflow.finished(store.one("tasks.find",task),delta);
        assertThat(store.one("tasks.find",task)).containsEntry("status","READY");
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(1);
    }
}
