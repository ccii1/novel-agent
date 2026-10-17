package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.MemoryService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryExtractionAgent implements NovelAgent {
    private final AgentContextService context;
    private final ModelClient model;
    private final MemoryService memory;
    private final Json json;
    public MemoryExtractionAgent(AgentContextService context,ModelClient model,MemoryService memory,Json json) { this.context=context;this.model=model;this.memory=memory;this.json=json; }
    public Set<String> kinds() { return Set.of("MEMORY"); }
    public String purpose() { return "从已通过正文检查的章节提取记忆增量与来源证据"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var draft=context.draft(task);
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出：{summary:string,facts:[{key:string,kind:string,name:string,detail:string,active:boolean,evidence:string}],"+
                "relations:[{subject:string,predicate:string,object:string,detail:string,deleted:boolean,evidence:string}]}。"+
                "summary 不超过600字。kind 仅为 WORLD/CHARACTER/EVENT/FORESHADOW。伏笔可以附加 dueChapter 整数表示计划回收章节。"+
                "相同事实沿用已有 key；伏笔回收时沿用 key 并设置 active=false。关系改变时沿用 subject/predicate/object，结束旧关系设置 deleted=true。"+
                "人物获知秘密用 KNOWS 关系，秘密作为 object。evidence 必须逐字引用当前正文至少4个字符。只抽取已有事实，最多40条增量。"+
                "上下文="+context.context(task,contextFiles())+"；正文="+json.write(draft));
        memory.validateDelta(result,(String)draft.get("body"));
        if(result.path("summary").asText().length()>1200) throw new IllegalArgumentException("章节摘要过长");
        return result;
    }
}
