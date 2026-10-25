package dev.novel.agent;

import dev.novel.agent.validation.ReviewValidator;
import dev.novel.agent.validation.AgentOutputReader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.dto.AgentOutputs;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.ChapterQualityService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ChapterReviewAgent extends ReviewAgent {
    private final ChapterQualityService quality;
    private final AgentOutputReader outputs;
    public ChapterReviewAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator,ChapterQualityService quality,AgentOutputReader outputs) {
        super(context,model,json,validator);this.quality=quality;this.outputs=outputs;
    }
    public Set<String> kinds() { return Set.of("CHECK_CHAPTER","CHECK_CHARACTER","CHECK_PLOT","CHECK_PACING","CHECK_STYLE"); }
    public String purpose() { return "联合审核人物关系、动机与知情范围；剧情因果、时间线、世界规则与伏笔；冲突推进、代价、战斗与叙事循环；文风、重复表达与语义完整性。四个维度逐项给出结论"; }
    public List<String> contextFiles() {
        return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md","progress_tracker.md","dialogue-monitor.md","pattern-detection.md","style_guide.md");
    }
    protected String additionalSchema() {
        return "额外输出 dimensions:{character:boolean,plot:boolean,pacing:boolean,style:boolean}，true 表示该维度通过；每条 issue 额外填写 dimension=CHARACTER/PLOT/PACING/STYLE。"+
                "未通过的每个维度至少有一条对应问题；全部维度通过才能 approved=true。额外输出 narrative:{opening:string,activity:string,ending:string,resolution:string,pattern:string,climax:boolean,combat:boolean}。";
    }
    protected String additionalInput(Map<String,Object> task,String body) {
        return "；实际统计="+json.write(quality.measure(body))+"。篇幅仅记录整理建议，不能仅因字数差异提出问题。前三章关注开篇，每五章检查循环，每十章完整审计。";
    }
    protected void validateAdditional(JsonNode result,String body) {
        outputs.read(result.path("narrative"),AgentOutputs.Narrative.class);
        outputs.read(result.path("dimensions"),AgentOutputs.Dimensions.class);
        for(var issue:result.path("issues"))
            if(!Set.of("CHARACTER","PLOT","PACING","STYLE").contains(issue.path("dimension").asText()))
                throw new IllegalArgumentException("联合审核问题必须声明 dimension");
        quality.enforceStyle(result,body);
        for(var issue:result.path("issues")) if(!issue.has("dimension")) {
            ((ObjectNode)issue).put("dimension","STYLE");((ObjectNode)result.path("dimensions")).put("style",false);
        }
        boolean approved=true;
        for(String dimension:List.of("character","plot","pacing","style")) {
            boolean passed=result.path("dimensions").path(dimension).asBoolean();
            boolean hasIssues=false;
            for(var issue:result.path("issues")) if(issue.path("dimension").asText().equals(dimension.toUpperCase(java.util.Locale.ROOT))) hasIssues=true;
            if(passed==hasIssues) throw new IllegalArgumentException("联合审核维度结论与问题不一致："+dimension);
            approved&=passed;
        }
        if(approved!=result.path("approved").asBoolean()) throw new IllegalArgumentException("联合审核总结果与维度结论不一致");
    }
}
