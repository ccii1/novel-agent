package dev.novel;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.ProjectService;
import dev.novel.service.WorkflowService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_UI_FIXTURE",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/memory-repair-ui;DB_CLOSE_ON_EXIT=FALSE","novel.engine-enabled=false"})
class MemoryRepairUiFixtureTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired Json json;
    @Test void prepareIndependentDatabaseWithRecordedChapterAndMemoryReview() throws Exception {
        var recorded=json.read(Files.readString(Path.of(".work/combined-review-case.json")));
        var fixtures=new ArrayList<Map<String,Object>>();
        for(String action:java.util.List.of("edit","repair")) {
            String id=projects.create("记忆处理界面验证 "+action,recorded.path("premise").asText(),recorded.path("bible").asText(),10,BigDecimal.TEN,BigDecimal.ONE);
            int chapter=recorded.path("chapter").asInt();
            db.update("UPDATE novels SET chapter_no=?,status='CHECKING_MEMORY',plan=? WHERE id=?",chapter,json.write(recorded.path("plan")),id);
            db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,?,1,0,?,?,'DRAFT',0)",id,chapter,recorded.path("title").asText(),recorded.path("body").asText());
            String task=store.enqueue(id,"CHECK_MEMORY",chapter,0,0,"ui:"+UUID.randomUUID(),Map.of("delta",recorded.path("delta"),"repairAttempt",3));
            dispatch.claim(task);workflow.finished(store.one("tasks.find",task),recorded.path("review"));
            assertThat(projects.memoryReview(id)).containsEntry("pending",true);
            fixtures.add(Map.of("id",id,"action",action,"body",recorded.path("body"),"plan",recorded.path("plan")));
        }
        Files.writeString(Path.of(".work/memory-repair-ui-fixtures.json"),json.write(fixtures));
    }
}
