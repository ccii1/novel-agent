package dev.novel.agent;

import dev.novel.agent.validation.ReviewValidator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;

import java.util.*;

public abstract class ReviewAgent implements NovelAgent {
    public Set<String> tools() {
        return Set.of("read_memory", "read_paragraph");
    }

    protected final AgentContextService context;
    protected final ModelClient model;
    protected final Json json;
    private final ReviewValidator validator;

    protected ReviewAgent(AgentContextService context, ModelClient model, Json json, ReviewValidator validator) {
        this.context = context;
        this.model = model;
        this.json = json;
        this.validator = validator;
    }

    public List<String> contextFiles() {
        return AgentInstructions.storyFiles();
    }

    protected String additionalInput(Map<String, Object> task, String body) {
        return "";
    }

    protected String additionalSchema() {
        return "";
    }

    protected void validateAdditional(JsonNode result, String body) {
    }

    protected JsonNode validateReview(Map<String, Object> task, JsonNode result, String body) {
        return validator.validate(result, body);
    }

    protected ReviewValidator validator() {
        return validator;
    }

    public JsonNode execute(Map<String, Object> task) throws Exception {
        var draft = context.draft(task);
        String body = (String) draft.get("body");
        String draftInput = json.write(Map.of("title", draft.get("title"), "paragraphs", validator.paragraphs(body)));
        String additional = additionalInput(task, body);
        var result = model.call(task, AgentInstructions.SYSTEM + "你负责独立检查，不能修改正文或记忆。",
                "检查重点=" + purpose() + "。输出：{approved:boolean,majorChange:boolean,issues:[{severity:string,quote:string,reason:string,suggestion:string}]}。" +
                        "approved=true 时 issues 必须为空；只有改变核心剧情、人物重要转变、关键伏笔答案或阶段结局才设置 majorChange=true。" +
                        "证据不足时不得声称存在矛盾。每个问题填写 quoteSource=BODY 和 quotePath=/paragraphs/p0 等已提供的段落路径，选择至少4个字符的段落；" +
                        "quote 可省略，由程序读取所选段落的完整原文，禁止编造路径。比较两处表达时选择其中一段，reason 说明比较理由。" +
                        additionalSchema() +
                        "；上下文=" + context.context(task, contextFiles(), draftInput + additional) + "；正文=" + draftInput + additional);
        result = validateReview(task, result, body);
        validateAdditional(result, body);
        return result;
    }
}
