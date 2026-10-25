package dev.novel.agent;

import dev.novel.agent.validation.ReviewValidator;
import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.ChapterQualityService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ChapterReviewAgent implements NovelAgent {
    private final AgentContextService context;
    private final ModelClient model;
    private final Json json;
    private final ReviewValidator validator;
    private final ChapterQualityService quality;
    public ChapterReviewAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator,ChapterQualityService quality) {
        this.context=context;this.model=model;this.json=json;this.validator=validator;this.quality=quality;
    }
    public Set<String> kinds() { return Set.of("CHECK_CHAPTER"); }
    public Set<String> tools() { return Set.of("read_memory","read_paragraph"); }
    public String purpose() { return "检查正文的连续性、情节推进和表达质量"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("style_guide.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var draft=context.draft(task);String body=(String)draft.get("body");
        String input=json.write(Map.of("title",draft.get("title"),"paragraphs",validator.paragraphs(body),"measurements",quality.measure(body)));
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出 {approved:boolean,majorChange:boolean,planConflict:boolean,issues:[{severity:string,quoteSource:BODY,quotePath:string,reason:string,suggestion:string}]}。"+
                "检查人物知情与能力、物品和时间线、章纲目标是否完成、情节是否推进，以及明显重复表达。只提出有正文依据且影响内容质量的问题。"+
                "问题quotePath使用提供的/paragraphs/p0等真实路径。证据不足时读取原文，不编造问题。通过时issues必须为空。"+
                "重大剧情变更使用majorChange=true；章纲内部矛盾且正文修改无法解决时planConflict=true，在reason引用直接相关章纲原文。两种情况均等待人工确认。"+
                "篇幅差异只作为建议。上下文="+context.context(task,contextFiles(),input)+"；正文="+input);
        result=validator.validate(result,body);
        quality.enforceStyle(result,body);
        return result;
    }
}
