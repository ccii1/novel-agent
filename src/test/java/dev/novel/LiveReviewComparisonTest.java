package dev.novel;

import dev.novel.repository.Store;
import dev.novel.service.AgentRoles;
import dev.novel.service.AgentService;
import dev.novel.service.ProjectService;
import dev.novel.integration.model.ModelCatalog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_REVIEW_COMPARISON",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:review-comparison;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class LiveReviewComparisonTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired AgentService agents;
    @Autowired ModelCatalog catalog;
    @Autowired Json json;
    @Test @Timeout(600) void compareRealLocalReviewsAgainstTheSameRecordedChapter() throws Exception {
        var selection=catalog.resolve("local","gpt-5.6-terra","CHECK_CHAPTER");
        assertThat(selection.ready()).isTrue();
        assertThat(selection.billingMode()).isEqualTo("unmetered");
        var recorded=json.read(Files.readString(Path.of(".work/combined-review-case.json")));
        String id=projects.create("联合审核对照验证",recorded.path("premise").asText(),recorded.path("bible").asText(),10,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-5.6-terra");
        int chapter=recorded.path("chapter").asInt();
        db.update("UPDATE novels SET chapter_no=?,status='CHECKING',plan=? WHERE id=?",chapter,json.write(recorded.path("plan")),id);
        db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,?,1,0,?,?,'DRAFT',0)",id,chapter,recorded.path("title").asText(),recorded.path("body").asText());
        var results=new ArrayList<Map<String,Object>>();
        results.add(run(id,chapter,"CHECK_CHAPTER"));
        var pool=Executors.newFixedThreadPool(4);
        long started=System.nanoTime();
        try {
            List<Future<Map<String,Object>>> futures=new ArrayList<>();
            for(String role:AgentRoles.CHAPTER_CHECKS) futures.add(pool.submit(()->run(id,chapter,role)));
            for(var future:futures) results.add(future.get());
        } finally {pool.shutdownNow();}
        var report=Map.of("provider","local","model","gpt-5.6-terra","chapter",chapter,
                "parallelWallMs",(System.nanoTime()-started)/1_000_000,"results",results,
                "calls",db.list("SELECT t.kind,c.input_tokens,c.output_tokens,c.cached_input_tokens,c.actual,c.latency_ms FROM model_calls c JOIN tasks t ON t.id=c.task_id WHERE c.novel_id=?",id));
        Files.writeString(Path.of(".work/review-comparison-results.json"),json.write(report));
        assertThat(results).hasSize(5);
    }
    private Map<String,Object> run(String novel,int chapter,String kind) throws Exception {
        String id=store.enqueue(novel,kind,chapter,0,0,"comparison:"+UUID.randomUUID(),Map.of());
        db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",id);
        var task=store.one("tasks.find",id);
        long started=System.nanoTime();
        var result=agents.execute(task);
        store.update("tasks.done",json.write(result),id);
        return Map.of("kind",kind,"elapsedMs",(System.nanoTime()-started)/1_000_000,"result",result);
    }
}
