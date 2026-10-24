package dev.novel;

import dev.novel.agent.AgentContextService;
import dev.novel.agent.AgentInstructions;
import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_CONTEXT_LIVE_TEST",matches="true")
@ActiveProfiles("local")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:file:./.work/context-live;DB_CLOSE_ON_EXIT=FALSE",
        "novel.engine-enabled=false","novel.redis.enabled=false","novel.model.max-output-tokens=2048"})
class LiveToolContextTest {
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired ProjectService projects;
    @Autowired MemoryService memory;
    @Autowired AgentContextService context;
    @Autowired ModelClient model;
    @Autowired Json json;

    @Test @Timeout(180)
    void actualModelListsAndBatchReadsLargerMemoryResultsThenChecksSource() throws Exception {
        String id=projects.create("批量记忆工具验证","核对人物线索","保持人物知情范围",2,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-6.1-sol","low");
        store.update("tasks.cancelNovel",id);
        var facts=new ArrayList<Map<String,Object>>();
        for(int index=0;index<3;index++) facts.add(Map.of("key","batch-person-"+index,"kind","CHARACTER","name","人物"+index,
                "detail",("人物"+index+"记录钥匙线索。").repeat(70),"evidence","人物核对钥匙。"));
        memory.commit(id,1,1,json.tree(Map.of("summary","人物线索核对","facts",facts,"relations",List.of())));
        String proof="核验编号："+UUID.randomUUID();
        store.update("chapters.draft",id,1,1,0,"来源核验",proof,1);
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String taskId=store.enqueue(id,"WRITE",2,0,1,UUID.randomUUID().toString(),Map.of());
        var result=model.call(store.one("tasks.find",taskId),AgentInstructions.SYSTEM,
                "执行文件和来源读取验证，不创作小说。第一轮使用 list_memory_files 列出 characters 目录，limit=30。"+
                "第二轮根据目录结果调用一次 read_memory_batch，读取全部三个人物文件，跳过 INDEX.md，length=6000。"+
                "收到批量文件后，调用 read_source 读取 chapter=1、revision=0、paragraph=p0。"+
                "取得来源后仅输出 JSON {\"verifiedFiles\":3,\"source\":来源工具返回的完整 text}。不得猜测来源编号。");
        assertThat(result.path("verifiedFiles").asInt()).isEqualTo(3);
        assertThat(result.path("source").asText()).isEqualTo(proof);
        var events=db.list("SELECT message FROM events WHERE task_id=? AND type='AGENT_TOOL_USED' ORDER BY id",taskId);
        var messages=events.stream().map(row->json.read(Store.string(row,"message"))).toList();
        assertThat(messages.stream().map(row->row.path("tool").asText()).toList()).contains("list_memory_files","read_memory_batch","read_source");
        var batch=messages.stream().filter(row->row.path("tool").asText().equals("read_memory_batch")).findFirst().orElseThrow();
        assertThat(batch.path("responseBytes").asInt()).isBetween(12001,24000);
        var turns=db.list("SELECT request_body FROM model_call_turns WHERE task_id=? ORDER BY turn_no",taskId);
        assertThat(turns.size()).isBetween(3,4);
        for(var turn:turns) assertThat(Store.string(turn,"request_body").getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(120000);
        assertThat(db.one("SELECT status FROM model_calls WHERE task_id=?",taskId).get("status")).isEqualTo("COMPLETE");
    }
    @Test @Timeout(180)
    void actualModelCompletesTenMemoryReadsAndFinalJson() throws Exception {
        String id=projects.create("多文件工具验证","核对人物掌握的线索","保持人物知情范围",2,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-6.1-sol","low");
        store.update("tasks.cancelNovel",id);
        var facts=new ArrayList<Map<String,Object>>();
        for(int index=0;index<10;index++) facts.add(Map.of("key","person-"+index,"kind","CHARACTER","name","人物"+index,
                "detail","人物"+index+"掌握第"+index+"号钥匙线索。","evidence","人物核对钥匙。"));
        memory.commit(id,1,1,json.tree(Map.of("summary","人物线索核对","facts",facts,"relations",List.of())));
        db.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        String taskId=store.enqueue(id,"WRITE",2,0,1,UUID.randomUUID().toString(),Map.of());
        var paths=memory.files().manifest(id,1).stream().map(row->Store.string(row,"path"))
                .filter(path->path.startsWith("characters/") && !path.endsWith("/INDEX.md")).sorted().toList();
        assertThat(paths).hasSize(10);
        var result=model.call(store.one("tasks.find",taskId),AgentInstructions.SYSTEM,
                "执行文件读取验证。必须对下面十个不同路径分别调用一次 read_memory，offset=0、length=4000；请在同一轮提出十次调用。"+
                "每个路径都必须读取。收到十份结果后停止工具调用，仅输出 JSON {\"verifiedFiles\":10}，不要创作正文。路径="+json.write(paths));
        assertThat(result.path("verifiedFiles").asInt()).isEqualTo(10);
        var events=db.list("SELECT message FROM events WHERE task_id=? AND type='AGENT_TOOL_USED' ORDER BY id",taskId);
        assertThat(events).hasSize(10);
        assertThat(json.read(Store.string(events.get(9),"message")).path("remainingToolBytes").asInt()).isPositive();
        var turns=db.list("SELECT request_body FROM model_call_turns WHERE task_id=? ORDER BY turn_no",taskId);
        assertThat(turns.size()).isBetween(2,4);
        for(var turn:turns) assertThat(Store.string(turn,"request_body").getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(120000);
        assertThat(db.one("SELECT status FROM model_calls WHERE task_id=?",taskId).get("status")).isEqualTo("COMPLETE");
    }
    @Test @Timeout(180)
    void actualModelCompletesMemoryToolRoundTripWithLargeRetainedEvidence() throws Exception {
        String id=projects.create("上下文工具验证","林舟核对钥匙来源","保持人物知情范围",2,BigDecimal.TEN,BigDecimal.ONE,"local","gpt-6.1-sol","low");
        store.update("tasks.cancelNovel",id);
        String body="林舟核对钥匙上的编号，记录信封封口的形状。".repeat(160);
        var facts=new ArrayList<Map<String,Object>>();
        for(int index=0;index<30;index++) facts.add(Map.of("key","clue"+index,"kind","CHARACTER","name","林舟",
                "detail","林舟记录第"+index+"号线索，与钥匙和信封有关。","evidence",body));
        memory.commit(id,1,1,memory.validateDelta(json.tree(Map.of("summary","林舟核查信件","facts",facts,"relations",List.of())),body));
        db.update("UPDATE novels SET memory_version=1,chapter_no=2 WHERE id=?",id);
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","核对钥匙","coreMilestones",List.of("核查来源"),"chapters",List.of(
                Map.of("number",2,"title","封口","goal","核对封口","characters",List.of("林舟"),"foreshadowIds",List.of()),
                Map.of("number",3,"title","来信","goal","核对来信","characters",List.of("林舟"),"foreshadowIds",List.of())))),id);
        assertThat(json.write(memory.snapshot(id,1)).getBytes(StandardCharsets.UTF_8).length).isGreaterThan(120000);
        projects.approvePlan(id,null);
        String taskId=Store.string(db.one("SELECT id FROM tasks WHERE novel_id=? AND kind='WRITE' AND status='WAITING'",id),"id");
        var task=store.one("tasks.find",taskId);
        String prompt="First call search_memory exactly once with query=clue0, directory=characters and limit=1. "
                +"After receiving its result, stop using tools and return only JSON {\"answer\":391}. Do not write fiction. Context="
                +context.context(task,AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"));
        var result=model.call(task,"Verify one real memory tool round trip and then return the requested JSON.",prompt);
        assertThat(result.path("answer").asInt()).isEqualTo(391);
        var turns=db.list("SELECT * FROM model_call_turns WHERE task_id=? ORDER BY turn_no",taskId);
        assertThat(turns).hasSize(2);
        assertThat(json.read(Store.string(turns.get(0),"response_body")).path("choices").path(0).path("message")
                .path("tool_calls").path(0).path("function").path("name").asText()).isEqualTo("search_memory");
        for(var turn:turns) assertThat(Store.string(turn,"request_body").getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(120000);
        var call=db.one("SELECT status,actual FROM model_calls WHERE task_id=?",taskId);
        assertThat(call.get("status")).isEqualTo("COMPLETE");
        assertThat(call.get("actual").toString()).isEqualTo("0.000000");
        assertThat(memory.snapshot(id,1)).hasSize(30);
        System.out.println("context-live requestBytes="+turns.stream().map(row->Store.string(row,"request_body").getBytes(StandardCharsets.UTF_8).length).toList());
    }
}
