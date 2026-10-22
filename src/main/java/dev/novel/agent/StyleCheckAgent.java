package dev.novel.agent;

import dev.novel.agent.validation.ReviewValidator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.ChapterQualityService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class StyleCheckAgent extends ReviewAgent {
    private final ChapterQualityService quality;
    public StyleCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator,ChapterQualityService quality) {
        super(context,model,json,validator);this.quality=quality;
    }
    public Set<String> kinds() { return Set.of("CHECK_STYLE"); }
    public String purpose() { return "记录篇幅整理建议，检查文风、重复表达、过度解释和语义完整性；前三章检查开篇吸引力，每十章完整审计"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("style_guide.md","dialogue-monitor.md"); }
    protected String additionalInput(Map<String,Object> task,String body) {
        return "；实际统计="+json.write(quality.measure(body))+
                "。字数仅作为整理建议，程序会记录 lengthAdvice。不要仅因正文少于2000字或超过2500字判定未通过或提出问题；"+
                "只有具体的内容缺失、重复、表达或节奏问题才进入 issues，并引用正文证据。禁止为凑够字数扩写，禁止为压缩字数删除关键事实。";
    }
    protected void validateAdditional(JsonNode result,String body) { quality.enforceStyle(result,body); }
}
