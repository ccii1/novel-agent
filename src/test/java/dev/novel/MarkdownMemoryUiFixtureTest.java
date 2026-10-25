package dev.novel;

import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@EnabledIfEnvironmentVariable(named="NOVEL_MARKDOWN_UI_FIXTURE",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/markdown-ui;DB_CLOSE_ON_EXIT=FALSE",
        "novel.engine-enabled=false","novel.tests.keep-files=true","novel.memory-directory=./.work/tests/books/markdown-ui"})
class MarkdownMemoryUiFixtureTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired MemoryService memory;
    @Autowired WorkflowService workflow;
    @Autowired MemoryMarkdown markdown;
    @Autowired Json json;

    @Test void createsEditableMarkdownUsingRealApplicationServices() throws Exception {
        String id=projects.create("文件记忆界面验证","林舟交出钥匙","人物只知道亲历信息",1,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        String body="林舟交出铜钥匙，陈岚将钥匙收入内袋。";
        db.update("UPDATE novels SET status='CHECKING_MEMORY',plan=? WHERE id=?","{\"chapters\":[{\"number\":1}]}",id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,1,1,0,'交付',?,'DRAFT',0)",id,body);
        var changes=new ArrayList<Map<String,Object>>();
        changes.add(Map.of("path","characters/char_001.md","expectedHash","","content",markdown.create("char_001","character","林舟",List.of("小舟"),
                "## 当前状态\n\n已经交出铜钥匙。来源：第1章 revision 0 p0。\n\n## 人物关系\n\n与陈岚同行。"),"description","林舟交出钥匙","evidencePaths",List.of("/paragraphs/p0")));
        for(String path:List.of("current_state.md","handoff_current.md")) changes.add(Map.of("path",path,"expectedHash",memory.files().hash(id,0,path),
                "content",markdown.create(path.equals("current_state.md")?"current-state":"handoff",path.equals("current_state.md")?"state":"handoff",path,
                        List.of(),"林舟已经交出铜钥匙，陈岚负责保管。"),"description","更新交付后的状态","evidencePaths",List.of("/paragraphs/p0")));
        var delta=memory.validateDelta(json.tree(Map.of("summary","林舟交出钥匙。","files",changes)),body);
        memory.commit(id,1,1,delta);
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        var adjusted=new ArrayList<Map<String,Object>>();
        for(var change:changes) {
            var update=new LinkedHashMap<>(change);update.put("expectedHash",memory.files().hash(id,1,(String)change.get("path")));adjusted.add(update);
        }
        var pending=memory.validateDelta(json.tree(Map.of("summary","林舟交出钥匙。","files",adjusted)),body);
        String task=store.enqueue(id,"CHECK_MEMORY",1,0,1,UUID.randomUUID().toString(),Map.of("delta",pending,"repairAttempt",3));
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task);
        workflow.finished(store.one("tasks.find",task),json.read("{\"approved\":false,\"majorChange\":false,\"issues\":[{\"severity\":\"minor\",\"quoteSource\":\"MEMORY\",\"quotePath\":\"/files/0/content\",\"quote\":\"已经交出铜钥匙\",\"reason\":\"请核对人物关系来源\",\"suggestion\":\"补充人物关系的正文依据\"}]}"));
        Files.writeString(Path.of(".work/markdown-ui-fixture.json"),json.write(Map.of("novelId",id,"taskId",task)));
    }
}
