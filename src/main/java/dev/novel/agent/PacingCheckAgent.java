package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.ChapterQualityService;
import dev.novel.support.Json;
import dev.novel.dto.AgentOutputs;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PacingCheckAgent extends ReviewAgent {
    private final ChapterQualityService quality;
    private final AgentOutputReader outputs;
    public PacingCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator,ChapterQualityService quality,AgentOutputReader outputs) {
        super(context,model,json,validator);this.quality=quality;this.outputs=outputs;
    }
    public Set<String> kinds() { return Set.of("CHECK_PACING"); }
    public String purpose() { return "冲突推进、失败代价、信息揭示、高潮与缓和、战斗场景、缺乏铺垫的说服解决、对话占比和追读意愿；每五章检查循环模式"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("progress_tracker.md","dialogue-monitor.md","pattern-detection.md"); }
    protected String additionalInput(Map<String,Object> task,String body) { return "；实际统计="+json.write(quality.measure(body)); }
    protected String additionalSchema() { return "额外输出 narrative:{opening:string,activity:string,ending:string,resolution:string,pattern:string,climax:boolean,combat:boolean}，描述本章结构，pattern 沿用历史相同模式名称。"; }
    protected void validateAdditional(JsonNode result,String body) {
        outputs.read(result.path("narrative"),AgentOutputs.Narrative.class);
    }
}
