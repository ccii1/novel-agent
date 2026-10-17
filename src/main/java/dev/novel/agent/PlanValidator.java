package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;

@Component
public class PlanValidator {
    private final Json json;
    public PlanValidator(Json json) { this.json=json; }
    public void validate(JsonNode plan,int first,int size) {
        json.text(plan,"stageGoal");
        if(!plan.path("coreMilestones").isArray() || plan.path("coreMilestones").isEmpty()) throw new IllegalArgumentException("大纲需要核心剧情节点");
        if(!plan.path("chapters").isArray() || plan.get("chapters").size()!=size) throw new IllegalArgumentException("大纲章节数量无效");
        for(var chapter:plan.get("chapters")) {
            if(!chapter.path("number").isInt() || chapter.get("number").asInt()!=first++) throw new IllegalArgumentException("大纲章节编号必须连续");
            json.text(chapter,"title");json.text(chapter,"goal");
            if(!chapter.path("characters").isArray() || !chapter.path("foreshadowIds").isArray()) throw new IllegalArgumentException("章节缺少人物或伏笔列表");
        }
    }
}
