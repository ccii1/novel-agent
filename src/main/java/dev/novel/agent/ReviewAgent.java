package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import java.util.*;

public abstract class ReviewAgent implements NovelAgent {
    protected final AgentContextService context;
    protected final ModelClient model;
    protected final Json json;
    private final ReviewValidator validator;
    protected ReviewAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator) {
        this.context=context;this.model=model;this.json=json;this.validator=validator;
    }
    public List<String> contextFiles() { return AgentInstructions.storyFiles(); }
    protected String additionalInput(Map<String,Object> task,String body) { return ""; }
    protected String additionalSchema() { return ""; }
    protected void validateAdditional(JsonNode result,String body) {}
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var draft=context.draft(task);
        String body=(String)draft.get("body");
        var result=model.call(task,AgentInstructions.SYSTEM+"你负责独立检查，不能修改正文或记忆。",
                "检查重点="+purpose()+"。输出：{approved:boolean,majorChange:boolean,issues:[{severity:string,quote:string,reason:string,suggestion:string}]}。"+
                "approved=true 时 issues 必须为空；只有改变核心剧情、人物重要转变、关键伏笔答案或阶段结局才设置 majorChange=true。"+
                "证据不足时不得声称存在矛盾。所有 quote 必须来自当前正文。"+additionalSchema()+
                "；上下文="+context.context(task,contextFiles())+"；正文="+json.write(Map.of("title",draft.get("title"),"body",body))+
                additionalInput(task,body));
        validator.validate(result,body);
        validateAdditional(result,body);
        return result;
    }
}
