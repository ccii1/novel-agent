package dev.novel;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.TaskDispatchMapper;
import dev.novel.service.PlanChangeService;
import dev.novel.service.ProjectService;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:plan-changes;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class PlanChangeIntegrationTest {
    @Autowired TestDatabase db;
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired PlanChangeService changes;
    @Autowired TaskDispatchMapper dispatch;
    @Autowired Json json;
    @LocalServerPort int port;

    private String novel() {
        String id=projects.create("城门调查","调查封闭城门","人物判断保留确定程度",2,BigDecimal.TEN,BigDecimal.ONE);
        store.update("tasks.cancelNovel",id);
        var chapters=List.of(Map.of("number",1,"title","城门","goal","调查封条","characters",List.of("陆禾"),"foreshadowIds",List.of("gate-seal")),
                Map.of("number",2,"title","名单","goal","核对名单","characters",List.of("陆禾"),"foreshadowIds",List.of("missing-name")));
        store.update("novels.awaitPlan",json.write(Map.of("stageGoal","找到出城条件","coreMilestones",List.of("辨认封条来源"),"chapters",chapters)),id);
        return id;
    }
    @Test void realHttpQueuesPersistedSuggestionWithoutChangingOutline() throws Exception {
        String id=novel();String before=Store.string(store.novel(id),"plan");
        var response=AuthHttp.owner(port).request("/api/novels/"+id+"/plan-changes",json.write(Map.of("path","/chapters/1/goal","instructions","延迟人物信任变化，保留名单线索")));
        assertThat(response.statusCode()).isEqualTo(200);
        var change=store.one("planChanges.find",json.read(response.body()).path("id").asText());
        assertThat(change).containsEntry("base_plan",before).containsEntry("status","GENERATING");
        assertThat(store.one("tasks.find",change.get("task_id"))).containsEntry("kind","PLAN_CHANGE").containsEntry("status","WAITING");
        assertThat(store.novel(id)).containsEntry("plan",before).containsEntry("status","AWAITING_PLAN");
        assertThat(changes.detail(id).get("targets")).asList().hasSize(8);
        assertThatThrownBy(()->changes.request(id,"/stageGoal","并发修改")).hasMessageContaining("等待");
        assertThatThrownBy(()->changes.approve(id,Store.string(change,"id"),true,true)).hasMessageContaining("等待");
    }
    @Test void onlySelectedFieldCanBeMergedAndInvalidValuesFail() {
        String id=novel();String changeId=changes.request(id,"/chapters/1/goal","保留怀疑");
        var change=changes.input(changeId);
        var before=json.read(Store.string(change,"base_plan"));
        var suggestion=json.tree(Map.of("replacement","核对名单时继续保留怀疑","reason","延迟信任变化","coreChange",true,"impacts",List.of(Map.of("chapter",2,"reason","动机延后"))));
        var merged=changes.validate(change,suggestion);
        var expected=before.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)expected.at("/chapters/1")).put("goal","核对名单时继续保留怀疑");
        assertThat(merged).isEqualTo(expected);
        assertThat(json.read(Store.string(store.novel(id),"plan"))).isEqualTo(before);
        var wrongType=suggestion.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongType).set("replacement",json.tree(List.of("类型错误")));
        assertThatThrownBy(()->changes.validate(change,wrongType)).hasMessageContaining("类型");
        var invalidImpact=suggestion.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalidImpact).set("impacts",json.tree(List.of(Map.of("chapter",999,"reason","超出范围"))));
        assertThatThrownBy(()->changes.validate(change,invalidImpact)).hasMessageContaining("不存在的章节");
    }
    @Test void staleMemoryChapterStageOrOutlineRejectsSuggestion() {
        for(String column:List.of("memory_version","chapter_no","stage_no")) {
            String id=novel();String change=changes.request(id,"/stageGoal","延迟揭示");
            db.update("UPDATE novels SET "+column+"="+column+"+1 WHERE id=?",id);
            assertThatThrownBy(()->changes.input(change)).hasMessageContaining("版本已经变化");
        }
        String id=novel();String change=changes.request(id,"/stageGoal","延迟揭示");
        var plan=json.read(Store.string(store.novel(id),"plan"));((com.fasterxml.jackson.databind.node.ObjectNode)plan).put("stageGoal","新的阶段目标");
        store.update("novels.plan",json.write(plan),id);
        assertThatThrownBy(()->changes.input(change)).hasMessageContaining("版本已经变化");
    }
    @Test void unsupportedTargetsActiveWritingAndUnfinishedResultsAreRejected() {
        String id=novel();
        assertThatThrownBy(()->changes.request(id,"/chapters/0/number","修改编号")).hasMessageContaining("有效");
        assertThatThrownBy(()->changes.request(id,"/chapters","替换全部")).hasMessageContaining("有效");
        store.update("novels.state","WRITING",id);
        assertThatThrownBy(()->changes.request(id,"/stageGoal","修改目标")).hasMessageContaining("暂停");
        store.update("novels.state","PAUSED",id);
        String task=store.enqueue(id,"WRITE",1,0,0,UUID.randomUUID().toString(),Map.of());
        store.update("tasks.ready","{}",task);
        assertThatThrownBy(()->changes.request(id,"/stageGoal","修改目标")).hasMessageContaining("处理完成");
    }
    @Test void revisionUsesCurrentSavedOutlineAndQueuesOnlyCurrentChapterRewrite() {
        String id=novel();store.update("novels.state","AWAITING_CHANGE",id);
        store.update("chapters.draft",id,1,1,0,"城门","陆禾查看城门封条。",0);
        String before=Store.string(store.novel(id),"plan");
        projects.revise(id,"按当前大纲保留悬念",null,true);
        assertThat(store.novel(id)).containsEntry("status","WRITING").containsEntry("plan",before);
        assertThat(store.one("tasks.byNovel",id)).containsEntry("kind","REWRITE").containsEntry("chapter_no",1);
    }
    @Test void stageApprovalUsesSavedOutlineWithoutSendingFullDocument() throws Exception {
        String id=novel();String before=Store.string(store.novel(id),"plan");
        var response=AuthHttp.owner(port).request("/api/novels/"+id+"/approve-plan?useCurrentPlan=true","{}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(store.novel(id)).containsEntry("plan",before).containsEntry("status","WRITING");
        assertThat(store.one("tasks.byNovel",id)).containsEntry("kind","WRITE");
    }
}
