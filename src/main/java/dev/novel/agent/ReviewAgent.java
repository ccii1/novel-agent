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
                "证据不足时不得声称存在矛盾。每个 quote 必须逐字复制当前正文中至少4个字符的单个连续片段，"+
                "保持原有标点与换行，禁止拼接多处引用，禁止增加省略号。比较两处表达时，quote 选择其中一个连续片段，reason 说明比较理由。"+additionalSchema()+
                "；上下文="+context.context(task,contextFiles())+"；正文="+json.write(Map.of("title",draft.get("title"),"body",body))+
                additionalInput(task,body));
        validator.validate(result,body);
        validateAdditional(result,body);
        return result;
    }
}
