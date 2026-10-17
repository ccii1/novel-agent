package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import dev.novel.dto.AgentOutputs;
import java.util.*;

public abstract class ChapterAuthorAgent implements NovelAgent {
    protected final AgentContextService context;
    protected final ModelClient model;
    protected final Json json;
    private final AgentOutputReader outputs;
    protected ChapterAuthorAgent(AgentContextService context,ModelClient model,Json json,AgentOutputReader outputs) { this.context=context;this.model=model;this.json=json;this.outputs=outputs; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md","style_guide.md","dialogue-monitor.md"); }
    protected abstract String instructions(Map<String,Object> task);
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出完整中文小说章节，正文2000至2500个非空白字符；结构：{title:string,body:string}。不要输出摘要或作者说明。"+
                "写作前读取 Truth Files，完成后进行文风自查，保持语义完整；含引号的非空行占比不超过40%。"+
                "不得自行改变核心剧情、人物重要转变、关键伏笔答案或阶段结局。上下文="+context.context(task,contextFiles())+instructions(task));
        String body=outputs.read(result,AgentOutputs.Chapter.class).body();
        if(body.codePointCount(0,body.length())<1800 || body.codePointCount(0,body.length())>3800)
            throw new IllegalArgumentException("章节正文长度应为1800至3800个字符");
        return result;
    }
}
