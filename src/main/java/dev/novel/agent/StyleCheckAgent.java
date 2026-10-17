package dev.novel.agent;

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
    public String purpose() { return "字数、文风、重复表达、过度解释和语义完整性；前三章检查开篇吸引力，每十章完整审计"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("style_guide.md","dialogue-monitor.md"); }
    protected String additionalInput(Map<String,Object> task,String body) { return "；实际统计="+json.write(quality.measure(body)); }
    protected void validateAdditional(JsonNode result,String body) { quality.enforceStyle(result,body); }
}
