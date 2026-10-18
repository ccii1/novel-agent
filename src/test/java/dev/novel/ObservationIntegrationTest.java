package dev.novel;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.*;
import dev.novel.support.Json;
import dev.novel.engine.TaskEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class ObservationIntegrationTest {
    @Test void workerPersistsFailureWhenModelIsUnconfigured() {
        String database="jdbc:h2:file:./.work/observation-worker-"+UUID.randomUUID()+";DB_CLOSE_ON_EXIT=FALSE";
        try(var app=new SpringApplicationBuilder(NovelApplication.class).run("--spring.datasource.url="+database,
                "--spring.main.web-application-type=none","--novel.engine-enabled=true","--novel.redis.enabled=false",
                "--novel.model.endpoint=","--novel.model.name=","--novel.model.api-key=")) {
            var store=app.getBean(Store.class);var observations=app.getBean(ObservationService.class);
            String id=app.getBean(ResearchService.class).create(List.of(),"职业悬疑");
            app.getBean(TaskEngine.class).dispatch();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(store.one("tasks.find",id).get("status")).isEqualTo("ERROR"));
            var detail=observations.detail(id);
            var executions=(List<?>)detail.get("executions");assertThat(executions).hasSize(1);
            var execution=(Map<?,?>)executions.get(0);
            assertThat(execution.get("status")).isEqualTo("ERROR");
            assertThat(execution.get("error").toString()).contains("请配置模型");
            assertThat(execution.get("duration_ms")).isNotNull();
            assertThat(detail.get("call")).isNull();
            var progress=app.getBean(TaskProgressService.class).progress(id);
            assertThat(progress.get("stage").toString()).contains("执行失败");
            assertThat(((Map<?,?>)progress.get("task")).get("elapsed_ms")).isNotNull();
        }
    }
    @Test void fileDatabaseRetainsExecutionsCostsReviewsAndFeedbackAcrossRestart() {
        String database="jdbc:h2:file:./.work/observation-"+UUID.randomUUID()+";DB_CLOSE_ON_EXIT=FALSE";
        String[] args={"--spring.datasource.url="+database,"--novel.engine-enabled=false",
                "--spring.main.web-application-type=none","--novel.redis.enabled=false"};
        String taskId;
        try(var app=new SpringApplicationBuilder(NovelApplication.class).run(args)) {
            var store=app.getBean(Store.class);var observations=app.getBean(ObservationService.class);
            var dispatch=app.getBean(TaskDispatchMapper.class);var calls=app.getBean(ModelCallMapper.class);
            var budget=app.getBean(BudgetService.class);var json=app.getBean(Json.class);
            taskId=store.enqueue(null,"CHECK_STYLE",1,0,0,UUID.randomUUID().toString(),Map.of());
            var task=store.transaction(() -> {
                var candidate=dispatch.nextCandidate();assertThat(candidate.get("id")).isEqualTo(taskId);
                assertThat(dispatch.claim(taskId)).isEqualTo(1);observations.start(candidate);return candidate;
            });
            budget.reserve(task,new BigDecimal("0.5"),"accounting-fixture",BigDecimal.ONE,BigDecimal.ONE);
            String callId=(String)((Map<?,?>)observations.detail(taskId).get("call")).get("id");
            // 直接检查真实数据库的记账与统计，不替代模型服务。
            calls.complete("COMPLETE",new BigDecimal("0.25"),100,150,"{}",callId);
            calls.observation(callId,125,"{\"prompt_tokens_details\":{\"cached_tokens\":80}}",80L);
            var review=json.read("{\"approved\":false,\"majorChange\":false,\"issues\":[{\"reason\":\"字符数量\"}]}");
            store.transaction(() -> {
                store.update("tasks.ready",json.write(review),taskId);
                observations.finish(task,"READY",review,null);return null;
            });
            var restored=store.transaction(() -> {
                var candidate=dispatch.nextCandidate();assertThat(dispatch.claim(taskId)).isEqualTo(1);
                observations.start(candidate);return candidate;
            });
            store.transaction(() -> {
                store.update("tasks.done",json.write(review),taskId);observations.finish(restored,"DONE",review,null);return null;
            });
            observations.feedback(taskId,2,"检查理由需要补充证据");
            observations.feedback(taskId,4,"复核后记录新的评价");
            assertThatThrownBy(()->observations.feedback(taskId,6,"超出评分范围")).isInstanceOf(IllegalArgumentException.class);
            var summary=(List<?>)observations.dashboard(null,1,"CHECK_STYLE",null,0).get("summary");
            var row=(Map<?,?>)summary.get(0);
            assertThat(((Number)row.get("model_calls")).intValue()).isEqualTo(1);
            assertThat(((Number)row.get("execution_count")).intValue()).isEqualTo(2);
            assertThat((BigDecimal)row.get("settled_cost")).isEqualByComparingTo("0.25");
            assertThat(((Number)row.get("issue_count")).intValue()).isEqualTo(1);
            assertThat(((Number)row.get("rejected_reviews")).intValue()).isEqualTo(1);
            assertThat(((Number)row.get("human_rating")).intValue()).isEqualTo(4);
            assertThat(((Number)row.get("cache_reported_calls")).intValue()).isEqualTo(1);
            var completedProgress=app.getBean(TaskProgressService.class).progress(taskId);
            assertThat(completedProgress.get("stage")).isEqualTo("回复已生成并保存");
            var publicTask=(Map<?,?>)completedProgress.get("task");
            for(String field:List.of("payload","response","result","api_key")) assertThat(publicTask.containsKey(field)).isFalse();
            assertThat(((Number)observations.dashboard(null,null,"PLAN",null,0).get("total")).intValue()).isZero();
            String interrupted=store.enqueue(null,"PLAN",0,0,0,UUID.randomUUID().toString(),Map.of());
            store.transaction(() -> {
                var candidate=dispatch.nextCandidate();assertThat(candidate.get("id")).isEqualTo(interrupted);
                dispatch.claim(interrupted);observations.start(candidate);return null;
            });
            assertThatThrownBy(()->observations.feedback(interrupted,3,"没有输出")).isInstanceOf(IllegalArgumentException.class);
        }
        try(var app=new SpringApplicationBuilder(NovelApplication.class).run(args)) {
            var observations=app.getBean(ObservationService.class);
            var detail=observations.detail(taskId);
            assertThat((List<?>)detail.get("executions")).hasSize(2);
            assertThat((List<?>)detail.get("feedback")).hasSize(2);
            var call=(Map<?,?>)detail.get("call");
            assertThat(((Number)call.get("cached_input_tokens")).longValue()).isEqualTo(80);
            assertThat(call.get("execution_id")).isNotNull();
            var planDashboard=observations.dashboard(null,null,"PLAN",null,0);
            var plan=(Map<?,?>)((List<?>)planDashboard.get("tasks")).get(0);
            var attempts=(List<?>)observations.detail(plan.get("id").toString()).get("executions");
            var attempt=(Map<?,?>)attempts.get(0);
            assertThat(attempt.get("status")).isEqualTo("INTERRUPTED");
            assertThat(attempt.get("finished_at")).isNull();
            assertThat(attempt.get("duration_ms")).isNull();
            assertThatThrownBy(()->observations.dashboard(null,null,null,null,-1)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
