package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.agent.AgentContextService;
import dev.novel.agent.AgentInstructions;
import dev.novel.agent.runtime.StoryReadTools;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.MemoryFileMapper;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.security.UserScope;
import dev.novel.service.*;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:markdown-memory;DB_CLOSE_DELAY=-1","novel.engine-enabled=false",
        "novel.memory-directory=./.work/tests/books/markdown"})
class MarkdownMemoryIntegrationTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired MemoryService memory;
    @Autowired MemoryFileService files;
    @Autowired MemoryFileMapper manifests;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired MemoryMarkdown markdown;
    @Autowired Json json;
    @Autowired AgentContextService context;
    @Autowired TruthDocumentService truth;
    @Autowired dev.novel.agent.validation.ReviewValidator validator;
    @LocalServerPort int port;
    private static final String BODY="林舟把铜钥匙交给陈岚。\n\n陈岚收起钥匙，林舟走向城门。";

    private String novel() {
        String id=projects.create("文件记忆验证","林舟调查钥匙","保持人物知情范围",1,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        db.update("UPDATE novels SET status='CHECKING_MEMORY',plan=? WHERE id=?","{\"chapters\":[{\"number\":1,\"characters\":[\"林舟\"],\"foreshadowIds\":[]}]}",id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,1,1,2,'交付',?,'DRAFT',0)",id,BODY);
        return id;
    }
    private Map<String,Object> change(String id,String path,String key,String type,String name,String body) {
        return Map.of("path",path,"expectedHash",files.hash(id,0,path),"content",markdown.create(key,type,name,List.of("小舟"),body),
                "description",name+"：记录钥匙交付后的状态与来源。","evidencePaths",List.of("/paragraphs/p0","/paragraphs/p1"));
    }
    private JsonNode delta(String id) {
        return memory.validateDelta(json.tree(Map.of("summary","林舟交出钥匙，陈岚保管钥匙。","files",List.of(
                change(id,"characters/char_001.md","char_001","character","林舟","## 当前状态\n\n已经交出铜钥匙。来源：第1章 revision 2 p0。\n\n## 知情范围\n\n不知道钥匙用途。"),
                change(id,"characters/char_002.md","char_002","character","陈岚","## 当前状态\n\n持有铜钥匙。来源：第1章 revision 2 p1。"),
                change(id,"current_state.md","current-state","state","当前状态","林舟走向城门，陈岚保管铜钥匙。"),
                change(id,"handoff_current.md","handoff","handoff","章节交接","下一章继续调查城门，核对两个人物主题。")))),BODY);
    }
    private Map<String,Object> source(String id) {
        String task=store.enqueue(id,"MEMORY",1,2,0,UUID.randomUUID().toString(),Map.of());
        return store.one("tasks.find",task);
    }
    @Test void filesBecomeVisibleOnlyAfterAllFileReviewsAndCoveragePass() {
        String id=novel();var delta=delta(id);
        files.validateBase(id,0,delta);
        String check=workflow.scheduleMemoryCheck(store.novel(id),source(id),delta,0);
        var groups=json.read(Store.string(store.one("tasks.find",check),"payload")).path("reviewGroups");
        var covered=new HashSet<Integer>();groups.forEach(group->group.forEach(item->assertThat(covered.add(item.asInt())).isTrue()));
        assertThat(covered).containsExactlyInAnyOrder(0,1,2,3);
        assertThat(groups.size()).isLessThan(delta.path("files").size());
        int reviews=groups.size()==1?1:groups.size()+1;
        for(int index=0;index<reviews;index++) {
            var task=store.one("tasks.find",check);var payload=json.read(Store.string(task,"payload"));
            assertThat(payload.path("reviewPhase").asText()).isEqualTo(groups.size()==1?"ALL":index<groups.size()?"FILES":"COVERAGE");
            assertThat(files.hash(id,0,"characters/char_001.md")).isEmpty();
            assertThat(dispatch.claim(check)).isEqualTo(1);
            workflow.finished(store.one("tasks.find",check),json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[]}"));
            if(index+1<reviews) check=Store.string(db.one("SELECT * FROM tasks WHERE novel_id=? AND kind='CHECK_MEMORY' AND status='WAITING'",id),"id");
        }
        assertThat(store.novel(id)).containsEntry("status","AWAITING_STAGE_REVIEW");
        assertThat(Store.number(store.novel(id),"memory_version")).isEqualTo(1);
        assertThat(files.readFull(id,1,"characters/char_001.md")).contains("已经交出铜钥匙");
        assertThat(files.readFull(id,1,"characters/char_002.md")).contains("持有铜钥匙");
        assertThat(files.readFull(id,1,"chapters/ch_0001.md")).contains("/paragraphs/p0");
        assertThat(db.one("SELECT COUNT(*) AS total FROM memories WHERE novel_id=?",id).get("total")).isEqualTo(0L);
    }
    @Test void databaseFailureLeavesNoPublishedManifestAndOldFilesRemainReadable() {
        String id=novel();var delta=delta(id);
        assertThatThrownBy(()->store.transaction(()-> {
            memory.commit(id,1,1,delta);throw new IllegalStateException("事务检查中断");
        })).hasMessageContaining("事务检查中断");
        assertThat(manifests.version(id,1)).isNull();
        assertThat(files.readFull(id,0,"MEMORY.md")).contains("记忆目录");
        assertThat(files.hash(id,0,"characters/char_001.md")).isEmpty();
        assertThat(store.one("chapters.find",id,1).get("status")).isEqualTo("DRAFT");
    }
    @Test void checksReferencesStableIdsConflictsAndPublishedFileIntegrity() throws Exception {
        String id=novel();var delta=delta(id);
        var conflict=delta.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)conflict.path("files").get(0)).put("expectedHash","incorrect");
        assertThatThrownBy(()->files.validateBase(id,0,conflict)).hasMessageContaining("重新读取");
        var invalid=delta.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("files").get(0)).putArray("evidencePaths").add("/paragraphs/p999");
        assertThatThrownBy(()->memory.validateDelta(invalid,BODY)).hasMessageContaining("证据不存在");
        memory.commit(id,1,1,delta);
        var row=manifests.file(id,1,"characters/char_001.md");
        Path physical=Path.of(".work/tests/books/markdown",id,"memory",Store.string(row,"physical_path"));
        Files.writeString(physical,Files.readString(physical)+"修改");
        assertThatThrownBy(()->files.readFull(id,1,"characters/char_001.md")).hasMessageContaining("长度");
    }
    @Test void searchPagingUnicodeAndHttpUseTheSameVersionedFiles() throws Exception {
        String id=novel();var delta=delta(id);memory.commit(id,1,1,delta);
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        var page=json.tree(files.search(id,1,"铜钥匙","characters",0,1,2000));
        assertThat(page.path("matches")).hasSize(1);assertThat(page.path("hasMore").asBoolean()).isTrue();
        String path=page.path("matches").get(0).path("path").asText();
        var content=json.tree(files.read(id,1,path,3,20,2000));
        assertThat(content.path("nextOffset").asInt()).isEqualTo(23);
        var response=AuthHttp.owner(port).request("/api/novels/"+id+"/memory-files/content?path="+path,null);
        assertThat(response.statusCode()).isEqualTo(200);assertThat(json.read(response.body()).path("content").asText()).contains("---");
        assertThatThrownBy(()->files.readFull(id,1,"../other.md")).hasMessageContaining("路径");
        try {
            UserScope.enter("different-user");assertThatThrownBy(()->files.readFull(id,1,path)).isInstanceOfAny(NoSuchElementException.class,IllegalStateException.class);
        } finally { UserScope.clear(); }
    }
    @Test void initialContextContainsShortEntryAndRequiredTopicsWithoutAllHistory() {
        String id=novel();memory.commit(id,1,1,delta(id));db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String task=store.enqueue(id,"WRITE",1,2,1,UUID.randomUUID().toString(),Map.of());
        var value=json.read(context.context(store.one("tasks.find",task),AgentInstructions.storyFiles()));
        assertThat(value.path("MEMORY.md").asText()).contains("characters/INDEX.md");
        assertThat(value.path("requiredTopics").path("characters/char_001.md").asText()).contains("已经交出铜钥匙");
        assertThat(value.toString()).doesNotContain("characters/char_002.md");
        var tools=new StoryReadTools(store.one("tasks.find",task),context,memory,store,json);
        assertThat(json.read(tools.search("小舟","characters",0,5)).path("total").asInt()).isEqualTo(2);
    }
    @Test void legacyVersionsKeepTheirOriginalChapterAndCanBeRestoredBeforeMigration() {
        String id=novel();
        db.update("UPDATE novels SET memory_version=3,chapter_no=4 WHERE id=?",id);
        for(int chapter=1;chapter<=3;chapter++) db.update("INSERT INTO memories(id,novel_id,memory_key,kind,chapter_no,version,content) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,"legacy-key","CHARACTER",chapter,chapter,
                json.write(Map.of("name","林舟","detail","第"+chapter+"章状态","evidence","林舟检查铜钥匙")));
        assertThat(memory.snapshot(id,3)).hasSize(1);
        assertThat(memory.snapshot(id,3).get(0).get("chapter_no")).isEqualTo(3);
        assertThat(Store.string(memory.snapshot(id,1).get(0),"content")).contains("第1章状态");
        store.transaction(()-> { files.restore(id,4,2);return null; });
        assertThat(Store.string(memory.snapshot(id,4).get(0),"content")).contains("第1章状态");
        assertThat(Store.string(memory.snapshot(id,3).get(0),"content")).contains("第3章状态");
        assertThat(db.one("SELECT COUNT(*) AS total FROM memories WHERE novel_id=?",id).get("total")).isEqualTo(3L);
    }
    @Test void fileReviewKeepsOnlyTheVerifiedQuoteAndRejectsInventedEvidence() {
        String id=novel();var pending=delta(id);
        var issue=Map.of("severity","minor","quoteSource","MEMORY","quotePath","/files/0/content","quote","已经交出铜钥匙",
                "reason","核对物品状态","suggestion","保留来源");
        var review=json.tree(Map.of("approved",false,"majorChange",false,"issues",List.of(issue)));
        var checked=validator.validate(review,BODY,pending);
        assertThat(checked.path("issues").get(0).path("quote").asText()).isEqualTo("已经交出铜钥匙");
        assertThat(checked.path("issues").get(0).path("quoteValue").asText()).isEqualTo("已经交出铜钥匙");
        ((com.fasterxml.jackson.databind.node.ObjectNode)review.path("issues").get(0)).put("quote","林舟保留铜钥匙");
        assertThatThrownBy(()->validator.validate(review,BODY,pending)).hasMessageContaining("连续匹配");
    }
    @Test void consolidatedTopicIsLoadedAndSearchedAheadOfItsLegacySources() {
        String id=novel();db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        db.update("INSERT INTO memories(id,novel_id,memory_key,kind,chapter_no,version,content) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,"old-lin-state","CHARACTER",1,1,
                json.write(Map.of("name","林舟","detail","此前持有铜钥匙","evidence","林舟检查铜钥匙")));
        files.ensure(id,1);
        var pending=delta(id);
        for(var item:pending.path("files")) ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("expectedHash",files.hash(id,1,item.path("path").asText()));
        memory.commit(id,1,2,pending);db.update("UPDATE novels SET memory_version=2 WHERE id=?",id);
        String task=store.enqueue(id,"WRITE",1,2,2,UUID.randomUUID().toString(),Map.of());
        var value=json.read(context.context(store.one("tasks.find",task),AgentInstructions.storyFiles()));
        assertThat(value.path("requiredTopics").path("characters/char_001.md").asText()).contains("已经交出铜钥匙");
        assertThat(value.path("requiredTopics").has(files.legacyPath("old-lin-state","CHARACTER"))).isFalse();
        var search=json.tree(files.search(id,2,"林舟","characters",0,1,4000));
        assertThat(search.path("matches").get(0).path("path").asText()).isEqualTo("characters/char_001.md");
    }
    @Test void chapterAliasesLoadTheCurrentTopicAndDeclareTheCompleteReadRange() {
        String id=novel();var pending=delta(id);
        for(var item:pending.path("files")) if(item.path("path").asText().equals("characters/char_001.md"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("content",markdown.create("char_001","character","林舟",List.of("持钥人"),"已经交出铜钥匙。来源：第1章 revision 2 p0。"));
        memory.commit(id,1,1,pending);
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","核对钥匙","chapters",List.of(Map.of("number",1,"characters",List.of("持钥人"),"foreshadowIds",List.of())))),id);
        var value=json.tree(truth.prompt(store.novel(id),1,AgentInstructions.storyFiles()));
        assertThat(value.path("requiredTopics").path("characters/char_001.md").asText()).contains("已经交出铜钥匙");
        var loaded=new ArrayList<JsonNode>();value.path("_loadedMemory").forEach(loaded::add);
        String original=files.readFull(id,1,"characters/char_001.md");
        assertThat(loaded).anySatisfy(row->{
            assertThat(row.path("path").asText()).isEqualTo("characters/char_001.md");
            assertThat(row.path("complete").asBoolean()).isTrue();
            assertThat(row.path("nextOffset").asInt()).isEqualTo(original.codePointCount(0,original.length()));
        });
    }
    @Test void legacyChapterTopicsProvideAllMatchingPathsWithoutReadingTheEntireDirectory() {
        String id=novel();var facts=new ArrayList<Map<String,Object>>();
        for(int index=0;index<8;index++) facts.add(Map.of("key","old-lin-"+index,"kind","CHARACTER","name","林舟","detail","线索"+index,"evidence","林舟核对钥匙。"));
        memory.commit(id,1,1,json.tree(Map.of("summary","核对钥匙","facts",facts,"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        var value=json.tree(truth.prompt(store.novel(id),1,AgentInstructions.storyFiles()));
        assertThat(value.path("unresolvedTopics").path("examples")).hasSize(8);
        assertThat(value.path("unresolvedTopics").path("hasMore").asBoolean()).isFalse();
        for(var row:value.path("unresolvedTopics").path("examples")) {
            assertThat(row.path("term").asText()).isEqualTo("林舟");
            assertThat(row.path("hash").asText()).hasSize(64);
        }
    }
    @Test void yamlAliasesKeepQuotedTextAndUnicodeWithWindowsLineEndings() {
        var names=List.of("持钥人","小舟：编号01","门前人😀");
        String content=markdown.create("char_001","character","林舟",names,"核对钥匙。").replace("\n","\r\n");
        assertThat(markdown.parse(content).aliases()).containsExactlyElementsOf(names);
    }
    @Test void ambiguousAliasesRemainExplicitCandidates() {
        String id=novel();memory.commit(id,1,1,delta(id));db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","核对人物身份","chapters",List.of(Map.of("number",1,"characters",List.of("小舟"),"foreshadowIds",List.of())))),id);
        var value=json.tree(truth.prompt(store.novel(id),1,AgentInstructions.storyFiles()));
        assertThat(value.has("requiredTopics")).isFalse();
        assertThat(value.path("unresolvedTopics").path("examples")).hasSize(2);
        for(var candidate:value.path("unresolvedTopics").path("examples")) assertThat(candidate.path("ambiguous").asBoolean()).isTrue();
    }
}
