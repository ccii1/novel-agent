package dev.novel.service;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.agent.AgentOutputReader;
import dev.novel.agent.PlanValidator;
import dev.novel.dto.AgentOutputs;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PlanChangeService {
    private static final Set<String> EDITABLE=Set.of("PAUSED","AWAITING_PLAN","AWAITING_CHANGE","AWAITING_HELP","AWAITING_STAGE_REVIEW");
    private final Store store;
    private final Json json;
    private final PlanValidator plans;
    private final AgentOutputReader outputs;
    public static boolean allowedStatus(Object status) { return EDITABLE.contains(status); }
    public PlanChangeService(Store store,Json json,PlanValidator plans,AgentOutputReader outputs) {
        this.store=store;this.json=json;this.plans=plans;this.outputs=outputs;
    }
    public Map<String,Object> detail(String id) {
        var novel=store.novel(id);
        var plan=json.read(Store.string(novel,"plan"));
        var history=store.list("planChanges.list",id).stream().map(change->{
            var row=new LinkedHashMap<>(change);
            row.put("target_label",targets(json.read(Store.string(change,"base_plan"))).stream()
                    .filter(target->target.get("path").equals(change.get("target_path"))).findFirst().orElseThrow().get("label"));
            row.put("stale",!matches(change,novel));
            row.remove("base_plan");row.remove("applied_plan");
            return row;
        }).toList();
        return Map.of("editable",EDITABLE.contains(novel.get("status")),"targets",targets(plan),"changes",history);
    }
    public List<Map<String,Object>> targets(JsonNode plan) {
        var targets=new ArrayList<Map<String,Object>>();
        if(plan==null || !plan.isObject()) return targets;
        targets.add(Map.of("path","/stageGoal","label","阶段目标","value",plan.path("stageGoal")));
        targets.add(Map.of("path","/coreMilestones","label","核心剧情节点","value",plan.path("coreMilestones")));
        for(int index=0;index<plan.path("chapters").size();index++) {
            var chapter=plan.path("chapters").get(index);
            String prefix="第"+chapter.path("number").asInt()+"章 · "+chapter.path("title").asText();
            for(String field:List.of("goal","characters","foreshadowIds")) {
                String label=switch(field) {case "goal" -> "情节与人物变化";case "characters" -> "出场人物";default -> "伏笔安排";};
                targets.add(Map.of("path","/chapters/"+index+"/"+field,"label",prefix+" · "+label,"value",chapter.path(field)));
            }
        }
        return targets;
    }
    public String request(String id,String path,String instructions) {
        if(instructions==null || instructions.isBlank() || instructions.length()>12000) throw new IllegalArgumentException("请输入修改要求，最多12000字符");
        return store.transaction(() -> {
            var novel=store.lockNovel(id);editable(novel);
            var plan=json.read(Store.string(novel,"plan"));
            target(plan,path);
            String change=UUID.randomUUID().toString();
            String task=store.enqueue(id,"PLAN_CHANGE",Store.integer(novel,"chapter_no"),0,Store.number(novel,"memory_version"),
                    id+":plan-change:"+change,Map.of("changeId",change));
            store.update("planChanges.create",change,id,task,novel.get("stage_no"),novel.get("memory_version"),novel.get("chapter_no"),json.write(plan),path,instructions);
            return change;
        });
    }
    public Map<String,Object> input(String changeId) {
        var change=store.one("planChanges.find",changeId);
        if(!"GENERATING".equals(change.get("status"))) throw new IllegalStateException("该修改建议已经生成");
        current(change,store.novel(Store.string(change,"novel_id")));
        return change;
    }
    public JsonNode validate(Map<String,Object> change,JsonNode suggestion) {
        outputs.read(suggestion,AgentOutputs.PlanChange.class);
        var plan=json.read(Store.string(change,"base_plan"));
        String path=Store.string(change,"target_path");
        target(plan,path);
        var replacement=suggestion.get("replacement");
        if(plan.at(path).getNodeType()!=replacement.getNodeType()) throw new IllegalArgumentException("修改建议的字段类型与原大纲不同");
        if(plan.at(path).equals(replacement)) throw new IllegalArgumentException("修改建议没有产生变化");
        var merged=merge(plan,path,replacement);
        plans.validate(merged,plan.path("chapters").get(0).path("number").asInt(),plan.path("chapters").size());
        var known=new HashSet<Integer>();
        plan.path("chapters").forEach(chapter->known.add(chapter.path("number").asInt()));
        store.list("chapters.byNovel",change.get("novel_id")).forEach(chapter->known.add(Store.integer(chapter,"number")));
        suggestion.path("impacts").forEach(impact->{if(!known.contains(impact.path("chapter").asInt())) throw new IllegalArgumentException("关联影响引用了不存在的章节");});
        return merged;
    }
    public void finished(Map<String,Object> task,JsonNode suggestion) {
        var change=input(json.read(Store.string(task,"payload")).path("changeId").asText());
        validate(change,suggestion);
        store.update("planChanges.ready",json.write(enrich(change,suggestion)),task.get("id"),change.get("id"));
        store.event(Store.string(change,"novel_id"),Store.string(task,"id"),"PLAN_CHANGE_READY","局部大纲修改建议已生成，请审核变化与影响范围");
    }
    public void approve(String novelId,String changeId,boolean acknowledgeCore,boolean acknowledgeCompleted) {
        store.transaction(() -> {
            var novel=store.lockNovel(novelId);editable(novel);
            var change=store.one("planChanges.find",changeId);
            if(!novelId.equals(change.get("novel_id"))) throw new IllegalArgumentException("修改建议不属于当前作品");
            if(!"PENDING".equals(change.get("status"))) throw new IllegalStateException("修改建议已经处理或尚未生成");
            current(change,novel);
            var suggestion=json.read(Store.string(change,"suggestion"));
            if(suggestion.path("coreChange").asBoolean() && !acknowledgeCore) throw new IllegalArgumentException("请确认核心剧情的审核范围");
            if(!suggestion.path("completedChapters").isEmpty() && !acknowledgeCompleted) throw new IllegalArgumentException("请确认涉及的已完成章节及历史正文处理范围");
            var merged=validate(change,suggestion);
            store.update("novels.plan",json.write(merged),novelId);
            store.update("planChanges.approve",json.write(merged),changeId);
            store.event(novelId,null,"PLAN_CHANGE_APPLIED","局部大纲修改已保存，版本记录="+changeId);
            return null;
        });
    }
    private JsonNode enrich(Map<String,Object> change,JsonNode suggestion) {
        var result=(ObjectNode)suggestion.deepCopy();
        var plan=json.read(Store.string(change,"base_plan"));
        String path=Store.string(change,"target_path");
        boolean core=suggestion.path("coreChange").asBoolean() || path.equals("/stageGoal") || path.equals("/coreMilestones")
                || path.endsWith("/characters") || path.endsWith("/foreshadowIds");
        result.put("coreChange",core);
        var affected=new TreeSet<Integer>();
        var pointer=JsonPointer.compile(path);
        if(pointer.getMatchingProperty().equals("chapters")) affected.add(plan.path("chapters").get(pointer.tail().getMatchingIndex()).path("number").asInt());
        else plan.path("chapters").forEach(chapter->affected.add(chapter.path("number").asInt()));
        suggestion.path("impacts").forEach(impact->affected.add(impact.path("chapter").asInt()));
        // 后续章节可能依赖当前变化，统一列入需要阅读的范围。
        int first=affected.first();
        store.list("chapters.byNovel",change.get("novel_id")).stream().filter(chapter->Store.integer(chapter,"number")>=first)
                .forEach(chapter->affected.add(Store.integer(chapter,"number")));
        plan.path("chapters").forEach(chapter->{if(chapter.path("number").asInt()>=first) affected.add(chapter.path("number").asInt());});
        var completed=new TreeSet<Integer>();
        store.list("chapters.byNovel",change.get("novel_id")).stream().filter(chapter->"ACCEPTED".equals(chapter.get("status")) && affected.contains(Store.integer(chapter,"number")))
                .forEach(chapter->completed.add(Store.integer(chapter,"number")));
        result.set("affectedChapters",json.tree(affected));
        result.set("completedChapters",json.tree(completed));
        result.set("before",plan.at(path));
        return result;
    }
    private void editable(Map<String,Object> novel) {
        if(!EDITABLE.contains(novel.get("status"))) throw new IllegalStateException("请在人工审核阶段修改大纲，或暂停创作后操作");
        if(!store.list("planChanges.unfinished",novel.get("id")).isEmpty()) throw new IllegalStateException("请等待正在执行或已经生成结果的任务处理完成");
    }
    private void current(Map<String,Object> change,Map<String,Object> novel) {
        if(!matches(change,novel)) throw new IllegalStateException("大纲、章节或记忆版本已经变化，请重新生成修改建议");
    }
    private boolean matches(Map<String,Object> change,Map<String,Object> novel) {
        return Store.integer(change,"stage_no")==Store.integer(novel,"stage_no") && Store.number(change,"base_memory")==Store.number(novel,"memory_version")
                && Store.integer(change,"base_chapter")==Store.integer(novel,"chapter_no") && json.read(Store.string(change,"base_plan")).equals(json.read(Store.string(novel,"plan")));
    }
    private void target(JsonNode plan,String path) {
        if(targets(plan).stream().noneMatch(target->target.get("path").equals(path))) throw new IllegalArgumentException("请选择有效的大纲修改范围");
    }
    private JsonNode merge(JsonNode plan,String path,JsonNode replacement) {
        var pointer=JsonPointer.compile(path);
        var merged=plan.deepCopy();
        ((ObjectNode)merged.at(pointer.head())).set(pointer.last().getMatchingProperty(),replacement.deepCopy());
        return merged;
    }
}
