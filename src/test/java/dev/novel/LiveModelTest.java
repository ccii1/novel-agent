package dev.novel;

import dev.novel.agent.orchestration.AgentRoles;

import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.integration.model.ModelClient;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.integration.model.AgentScopeExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_LIVE_TEST",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:live;DB_CLOSE_DELAY=-1","novel.engine-enabled=false","novel.review-mode=parallel"})
class LiveModelTest {
    @Autowired TestDatabase store;
    @Autowired ProjectService projects;
    @Autowired AgentService agents;
    @Autowired WorkflowService workflow;
    @Autowired ModelClient model;
    @Autowired ModelCatalog catalog;
    @Test @Timeout(600)
    void realProviderGeneratesPlanChapterAndIndependentReviews() throws Exception {
        assertThat(model.configured()).as("需要真实模型配置及计费方式").isTrue();
        String id=projects.create("密信与钥匙","悬疑网文：调查员发现一封尚未开封的密信，目标读者喜欢智斗和信息揭示。",
                "人物只能使用已经获知的信息，当前阶段需要完整小冲突和追读悬念。",1,new BigDecimal("20"),new BigDecimal("10"));
        run(next(id,"PLAN"));projects.approvePlan(id,null);run(next(id,"WRITE"));
        var checks=store.list("SELECT * FROM tasks WHERE novel_id=? AND status='WAITING' AND kind LIKE 'CHECK_%'",id);
        assertThat(checks).hasSize(AgentRoles.CHAPTER_CHECKS.size());
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(var task:checks) futures.add(pool.submit(()-> {run(task);return null;}));
            for(var future:futures) future.get();
        } finally {pool.shutdownNow();}
        assertThat(store.list("SELECT * FROM model_calls WHERE novel_id=? AND status='COMPLETE'",id)).hasSize(6);
        assertThat(store.list("SELECT * FROM model_calls WHERE novel_id=? AND skill_fingerprint IS NULL",id)).isEmpty();
        assertThat(store.list("SELECT * FROM model_calls WHERE novel_id=? AND agent_framework<>?",id,AgentScopeExecutor.FRAMEWORK)).isEmpty();
        assertThat(store.list("SELECT * FROM events WHERE novel_id=? AND type='AGENTSCOPE_REASONING_STARTED'",id)).hasSize(6);
        assertThat(store.list("SELECT * FROM events WHERE novel_id=? AND type='AGENTSCOPE_REASONING_FINISHED'",id)).hasSize(6);
        if(catalog.resolve(null,"","PLAN").billingMode().equals("unmetered")) {
            assertThat(store.list("SELECT * FROM model_calls WHERE novel_id=? AND (actual<>0 OR reserved<>0 OR billing_mode<>'unmetered' OR input_tokens<=0 OR output_tokens<=0)",id)).isEmpty();
        }
        assertThat(Store.number(store.novel(id),"memory_version")).isZero();
        assertThat(store.novel(id).get("status")).isIn("EXTRACTING","REFLECTING");
        if("EXTRACTING".equals(store.novel(id).get("status"))) {
            run(next(id,"MEMORY"));
            assertThat(store.novel(id).get("status")).isIn("AWAITING_STAGE_REVIEW","AWAITING_HELP");
        }
    }
    private Map<String,Object> next(String id,String kind) { return store.one("SELECT * FROM tasks WHERE novel_id=? AND kind=? AND status='WAITING'",id,kind); }
    private void run(Map<String,Object> task) throws Exception {
        store.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task.get("id"));
        workflow.finished(task,agents.execute(task));
    }
}
