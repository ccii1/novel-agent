package dev.novel.agent;

import dev.novel.agent.validation.ChapterOutputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WriterAgent implements NovelAgent {
    private final AgentContextService context;
    private final ModelClient model;
    private final Json json;
    private final ChapterOutputValidator chapters;
    public WriterAgent(AgentContextService context,ModelClient model,Json json,ChapterOutputValidator chapters) {
        this.context=context;this.model=model;this.json=json;this.chapters=chapters;
    }
    public Set<String> kinds() { return Set.of("WRITE","REWRITE"); }
    public Set<String> tools() { return Set.of("read_memory"); }
    public String purpose() { return "依据批准的章节目标创作或修改正文"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("style_guide.md","dialogue-monitor.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        String extra="";
        if("REWRITE".equals(task.get("kind"))) {
            var previous=new LinkedHashMap<>(task);
            previous.put("revision",((Number)task.get("revision")).intValue()-1);
            extra="；原章节="+json.stable(context.draft(previous))+"；修改要求="+context.novel(task).get("reflection");
        }
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出 {status:GENERATED,title:string,body:string}，正文参考2000至2500个非空白字符，保持情节完整，不输出摘要或执行说明。"+
                "审批状态以_workflow.planStatus为准。确实无法生成时输出 {status:BLOCKED,reason:string}。"+
                "完整呈现本章目标、因果、动作、结果与代价；依据审核意见修改时保留其他有效内容。"+
                "核对人物知情、物品、能力次数及世界规则。完成后自查重复表达和文风，含引号的非空行占比不超过40%。"+
                "重大剧情变更等待批准。上下文="+context.context(task,contextFiles(),extra)+extra);
        chapters.validate(result);
        return result;
    }
}
