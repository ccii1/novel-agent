package dev.novel.agent;

import dev.novel.agent.validation.AgentOutputReader;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import dev.novel.dto.AgentOutputs;
import java.util.*;

public abstract class ChapterAuthorAgent implements NovelAgent {
    public Set<String> tools() { return Set.of("read_memory","read_relations"); }
    protected final AgentContextService context;
    protected final ModelClient model;
    protected final Json json;
    private final AgentOutputReader outputs;
    protected ChapterAuthorAgent(AgentContextService context,ModelClient model,Json json,AgentOutputReader outputs) { this.context=context;this.model=model;this.json=json;this.outputs=outputs; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md","style_guide.md","dialogue-monitor.md"); }
    protected abstract String instructions(Map<String,Object> task);
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "先输出情节完整的中文小说章节，参考篇幅为2000至2500个非空白字符，允许随内容浮动；结构：{title:string,body:string}。不要输出摘要或作者说明。"+
                "优先完整呈现本章目标、因果和结尾，不为达到字数凑写，也不为压缩字数省略关键物证或人物动作。正文保存后再根据检查意见整理。"+
                "写作前读取 Truth Files，完成后进行文风自查，保持语义完整；含引号的非空行占比不超过40%。"+
                "不得自行改变核心剧情、人物重要转变、关键伏笔答案或阶段结局。上下文="+context.context(task,contextFiles())+instructions(task));
        outputs.read(result,AgentOutputs.Chapter.class);
        return result;
    }
}
