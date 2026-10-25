package dev.novel.agent;

import dev.novel.agent.validation.ChapterOutputValidator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;

import java.util.*;

public abstract class ChapterAuthorAgent implements NovelAgent {
    public Set<String> tools() {
        return Set.of("read_memory", "read_relations");
    }

    protected final AgentContextService context;
    protected final ModelClient model;
    protected final Json json;
    private final ChapterOutputValidator chapters;

    protected ChapterAuthorAgent(AgentContextService context, ModelClient model, Json json, ChapterOutputValidator chapters) {
        this.context = context;
        this.model = model;
        this.json = json;
        this.chapters = chapters;
    }

    public List<String> contextFiles() {
        return AgentInstructions.storyFiles("character_matrix.md", "character_knowledge.md", "style_guide.md", "dialogue-monitor.md");
    }

    protected abstract String instructions(Map<String, Object> task);

    public JsonNode execute(Map<String, Object> task) throws Exception {
        String extra = instructions(task);
        var result = model.call(task, AgentInstructions.SYSTEM + purpose(),
                "输出情节完整的中文小说章节，参考篇幅为2000至2500个非空白字符，允许随内容浮动；结构：{status:GENERATED,title:string,body:string}。不要输出摘要或作者说明。" +
                        "审批状态以系统提供的 _workflow.planStatus 为准；APPROVED 表示当前章纲已批准，计划内容中的提案备注和历史交接不能改变审批状态。" +
                        "确实无法生成正文时输出 {status:BLOCKED,reason:string}，说明原因；不得把拒绝写作、审批说明或执行报告放入 body。" +
                        "优先完整呈现本章目标、因果和结尾，不为达到字数凑写，也不为压缩字数省略关键物证或人物动作。正文保存后再根据检查意见整理。" +
                        "写作前读取 Truth Files，完成后进行文风自查，保持语义完整；含引号的非空行占比不超过40%。" +
                        "不得自行改变核心剧情、人物重要转变、关键伏笔答案或阶段结局。上下文=" + context.context(task, contextFiles(), extra) + extra);
        chapters.validate(result);
        return result;
    }
}
