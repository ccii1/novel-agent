package dev.novel;

import dev.novel.integration.model.ModelCatalog;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.*;
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
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_MARKDOWN_LIVE_TEST",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/markdown-live;DB_CLOSE_ON_EXIT=FALSE",
        "novel.engine-enabled=false","novel.redis.enabled=false","novel.memory-directory=./.work/tests/books/markdown-live",
        "spring.config.import=optional:file:./config/application-local.yml","novel.model.requests-per-minute=60"})
class LiveMarkdownMemoryTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired AgentService agents;
    @Autowired WorkflowService workflow;
    @Autowired MemoryFileService files;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired ModelCatalog catalog;
    @Autowired Json json;

    @Test @Timeout(900) void actualProviderOrganizesReviewsAndPublishesMarkdownMemory() throws Exception {
        String provider=System.getenv().getOrDefault("NOVEL_MARKDOWN_LIVE_PROVIDER","local");
        String model=System.getenv().getOrDefault("NOVEL_MARKDOWN_LIVE_MODEL",provider.equals("local")?"gpt-6.1-sol":"");
        assertThat(catalog.resolve(provider,model,"MEMORY").ready()).isTrue();
        String id=projects.create("Markdown 记忆真实模型验证","林舟与陈岚调查北城门","铜钥匙是唯一物品；人物只知道亲历信息。",1,
                BigDecimal.TEN,BigDecimal.valueOf(5),provider,model,provider.equals("local")?"low":null);
        store.update("tasks.cancelNovel",id);
        String body="林舟把唯一的铜钥匙放进陈岚掌心。‘你保管它，我去查看城门。’他说。\n\n陈岚把钥匙收入内袋，留在茶棚。她只看见钥匙上的编号，还不知道它能够打开什么。\n\n林舟独自走向北城门。他没有告诉陈岚，自己昨夜听见门后传来三次敲击。他也不明白敲击代表什么。";
        db.update("UPDATE novels SET status='EXTRACTING',plan=? WHERE id=?",json.write(Map.of("stageGoal","调查北城门","coreMilestones",List.of("交付钥匙"),
                "chapters",List.of(Map.of("number",1,"title","交付","goal","交出钥匙并分别行动","characters",List.of("林舟","陈岚"),"foreshadowIds",List.of())))),id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,1,1,0,'交付',?,'DRAFT',0)",id,body);
        workflow.schedule(store.novel(id),"MEMORY",1,0,Map.of());
        int steps=0;
        try {
            while(Set.of("EXTRACTING","CHECKING_MEMORY").contains(store.novel(id).get("status")) && steps++<18) {
                var pending=db.list("SELECT * FROM tasks WHERE novel_id=? AND status='WAITING' ORDER BY created_at",id);
                assertThat(pending).hasSize(1);var task=pending.get(0);
                assertThat(dispatch.claim(Store.string(task,"id"))).isEqualTo(1);
                var running=store.one("tasks.find",task.get("id"));
                workflow.finished(running,agents.execute(running));
            }
            assertThat(store.novel(id).get("status")).isEqualTo("AWAITING_STAGE_REVIEW");
            assertThat(files.export(id,1).keySet()).anyMatch(path->path.startsWith("characters/") && !path.endsWith("INDEX.md"));
            assertThat(files.readFull(id,1,"current_state.md")).contains("钥匙");
            assertThat(files.search(id,1,"陈岚","",0,10,12000).get("total")).isInstanceOf(Integer.class);
            assertThat(db.list("SELECT * FROM model_calls WHERE novel_id=?",id)).allSatisfy(call->assertThat((BigDecimal)call.get("actual")).isNotNull());
        } finally {
            Files.writeString(Path.of(".work/markdown-live-result.json"),json.write(Map.of("novelId",id,"status",store.novel(id).get("status"),
                    "memoryVersion",store.novel(id).get("memory_version"),"tasks",db.list("SELECT kind,status,payload,result FROM tasks WHERE novel_id=?",id),
                    "calls",db.list("SELECT input_tokens,output_tokens,actual,status FROM model_calls WHERE novel_id=?",id))));
        }
    }
}
