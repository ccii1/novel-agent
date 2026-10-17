package dev.novel;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:skills;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class SkillIntegrationTest {
    @Autowired SkillService skills;
    @Autowired ChapterQualityService quality;
    @Autowired TruthDocumentService truth;
    @Autowired ProjectService projects;
    @Autowired WorkflowService workflow;
    @Autowired TaskEngine engine;
    @Autowired BudgetService budget;
    @Autowired Store store;
    @Autowired Json json;
    @Autowired ModelClient model;
    @BeforeEach void clean() {
        for(String table:List.of("events","edit_requests","research","model_calls","chapter_metrics","memories","chapter_history","chapters","tasks","novels")) store.db.update("DELETE FROM "+table);
    }
    private String novel() { return projects.create("密信","围绕密信展开悬疑故事","保持人物知情范围",2,new BigDecimal("100"),new BigDecimal("5")); }
    @Test void everyRoleLoadsPinnedMainSkillAndRoleReferences() {
        for(String role:List.of("RESEARCH","PLAN","WRITE","REWRITE","CHECK_CHARACTER","CHECK_PLOT","CHECK_PACING","CHECK_STYLE","REFLECT","MEMORY","CHECK_MEMORY")) {
            var snapshot=skills.load(role,10);
            assertThat(snapshot.files()).contains("SKILL.md","references/pattern-detection.md","references/audit-dimensions.md");
            assertThat(snapshot.version()).isEqualTo("2.0.1");
            assertThat(snapshot.commit()).isEqualTo("7d874e032d2de3dace74ae585cd2dbfbac2cde69");
            assertThat(snapshot.instruction()).contains("Truth Files","2000至2500","当前任务以应用执行规则");
            assertThat(snapshot.fingerprint()).hasSize(64);
            assertThat(snapshot.instruction().getBytes(StandardCharsets.UTF_8).length).isLessThan(120000);
        }
    }
    @Test void roleAndReviewWindowDetermineReferenceFingerprint() {
        var normal=skills.load("CHECK_STYLE",4);var review=skills.load("CHECK_STYLE",10);
        assertThat(normal.files()).contains("references/ai-detox-guide.md").doesNotContain("references/pattern-detection.md");
        assertThat(normal.fingerprint()).isNotEqualTo(review.fingerprint());
        assertThat(skills.load("CHECK_STYLE",4).fingerprint()).isEqualTo(normal.fingerprint());
        assertThatThrownBy(()->skills.load("UNSUPPORTED",1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void measuresChineseQuotesAndIgnoresEmptyLines() {
        var value=quality.measure("林舟推开门。\n\n“钥匙在哪里？”沈月问。\n他指向窗台。\n仓库外响起脚步声。");
        assertThat(value.totalLines()).isEqualTo(4);assertThat(value.dialogueLines()).isEqualTo(1);
        assertThat(value.dialogueRatio()).isEqualTo(0.25);
        assertThat(value.characters()).isEqualTo(33);
    }
    @Test void styleRulesUseActualTextMeasurements() {
        String body="“钥匙在哪里？”\n“就在窗台。”\n林舟推开门。";
        var report=json.read("{\"approved\":true,\"majorChange\":false,\"issues\":[]}");
        quality.enforceStyle(report,body);
        assertThat(report.get("approved").asBoolean()).isFalse();
        assertThat(report.get("issues")).hasSize(2);
        report.get("issues").forEach(issue->assertThat(body).contains(issue.get("quote").asText()));
    }
    @Test void dialogueTrendUsesAcceptedCurrentRevisions() {
        String id=novel();
        for(int number=1;number<=3;number++) {
            String body="叙述\n叙述\n叙述\n叙述\n";
            body=body+"“询问”\n".repeat(number);
            store.db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,?,1,0,'密信',?,'ACCEPTED',0)",id,number,body);
            quality.record(id,number,0,body);
        }
        assertThat(quality.dashboard(id).get("dialogueRising")).isEqualTo(true);
        store.db.update("UPDATE chapters SET status='INVALIDATED' WHERE novel_id=? AND number=3",id);
        assertThat(quality.dashboard(id).get("dialogueRising")).isEqualTo(false);
        assertThat(quality.recent(id,10,20)).hasSize(2);
    }
    @Test void truthFilesAreReadableBeforeInitialPlanExists() {
        String id=novel();var files=truth.files(id);
        assertThat(files).containsKeys("current_state.md","pending_hooks.md","handoff_current.md","outline.md","progress_tracker.md","dialogue-monitor.md","pattern-detection.md");
        assertThat(files.get("handoff_current.md")).contains("\"completedChapter\":0","\"nextChapter\":1");
        assertThat(files.get("story_bible.md")).contains("围绕密信展开悬疑故事");
    }
    @Test void exportedArchiveContainsRealStateDocuments() throws Exception {
        String id=novel();Set<String> names=new HashSet<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(truth.archive(id)),StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                names.add(entry.getName());String text=new String(zip.readAllBytes(),StandardCharsets.UTF_8);
                assertThat(text).startsWith("# "+entry.getName());
                if(entry.getName().equals("story_bible.md")) assertThat(text).contains("密信");
            }
        }
        assertThat(names).hasSize(13).contains("handoff_current.md","character_knowledge.md");
    }
    @Test void checkTaskRegistrationIsIdempotentAndRecoversStyleCheck() {
        String id=novel();
        store.db.update("UPDATE novels SET status='CHECKING' WHERE id=?",id);
        store.db.update("INSERT INTO chapters(novel_id,number,stage_no,revision,title,body,status,memory_version) VALUES(?,1,1,0,'密信','林舟推开仓库大门。','DRAFT',0)",id);
        var novel=store.novel(id);
        for(String role:List.of("CHECK_CHARACTER","CHECK_PLOT","CHECK_PACING")) workflow.schedule(novel,role,1,0,Map.of());
        engine.recover();engine.recover();
        var checks=store.list("SELECT kind FROM tasks WHERE novel_id=? AND kind LIKE 'CHECK_%'",id);
        assertThat(checks).hasSize(4);
        assertThat(checks.stream().map(row->row.get("kind")).toList()).containsExactlyInAnyOrderElementsOf(AgentRoles.CHAPTER_CHECKS);
    }
    @Test void callReservationPersistsSkillManifestAndRequestWithoutApiKey() {
        String id=novel();var task=store.one("SELECT * FROM tasks WHERE novel_id=?",id);
        var snapshot=skills.load("PLAN",1);
        String request=json.write(Map.of("messages",List.of(Map.of("role","system","content",snapshot.instruction()))));
        budget.reserve(task,new BigDecimal("0.1"),"selected-provider-model",BigDecimal.ONE,BigDecimal.ONE,snapshot,request);
        var record=store.one("SELECT * FROM model_calls WHERE task_id=?",task.get("id"));
        assertThat(record.get("skill_fingerprint")).isEqualTo(snapshot.fingerprint());
        assertThat(record.get("skill_files")).asString().contains("SKILL.md","commercial-review.md");
        assertThat(record.get("request_body")).asString().contains("Truth Files").doesNotContain("Authorization","Bearer");
    }
    @Test void restorationRequiresRecordedSkillManifest() {
        String id=novel();var task=store.one("SELECT * FROM tasks WHERE novel_id=?",id);
        assertThatThrownBy(()->model.validateCheckpoint(task)).isInstanceOf(IllegalStateException.class).hasMessageContaining("技能版本");
        assertThat(store.list("SELECT * FROM model_calls")).isEmpty();
    }
}
