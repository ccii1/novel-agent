package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.MemoryService;
import dev.novel.support.Json;
import dev.novel.repository.Store;
import dev.novel.support.EvidenceMatcher;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryExtractionAgent implements NovelAgent {
    private final AgentContextService context;
    private final ModelClient model;
    private final MemoryService memory;
    private final Json json;
    private final EvidenceMatcher evidence;
    public MemoryExtractionAgent(AgentContextService context,ModelClient model,MemoryService memory,Json json,EvidenceMatcher evidence) { this.context=context;this.model=model;this.memory=memory;this.json=json;this.evidence=evidence; }
    public Set<String> kinds() { return Set.of("MEMORY","MEMORY_REPAIR"); }
    public String purpose() { return "从已通过正文检查的章节提取记忆增量与来源证据"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var draft=context.draft(task);
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出：{summary:string,facts:[{key:string,kind:string,name:string,detail:string,active:boolean,evidencePath:string}],"+
                "relations:[{subject:string,predicate:string,object:string,detail:string,deleted:boolean,evidencePath:string}]}。"+
                "summary 不超过600字。kind 仅为 WORLD/CHARACTER/EVENT/FORESHADOW。伏笔可以附加 dueChapter 整数表示计划回收章节。"+
                "相同事实沿用已有 key；伏笔回收时沿用 key 并设置 active=false。关系改变时沿用 subject/predicate/object，结束旧关系设置 deleted=true。"+
                "人物获知秘密用 KNOWS 关系，秘密作为 object。evidencePath 从当前正文的 paragraphs 段落选择，例如 /paragraphs/p0；"+
                "选择至少4个字符的证据段落，由程序读取完整原文作为 evidence，无需抄写。禁止编造路径或引用历史段落编号。只抽取当前正文已明确支持的事实，最多40条增量；历史记忆为空时仍可建立首批记忆。"+
                "人物推测、怀疑和可能性必须保持原有确定程度，不得写成已经确认的事实。"+
                ("MEMORY_REPAIR".equals(task.get("kind"))?"本次修正待审核记忆：只修改记忆，保持正文、大纲和已批准设定。逐项处理审核问题及人工要求，保留其他有证据的记忆和稳定 key。修正资料="+Store.string(task,"payload"):"")+
                "上下文="+context.context(task,contextFiles())+"；正文="+json.write(Map.of("title",draft.get("title"),"paragraphs",evidence.paragraphs((String)draft.get("body")))));
        return memory.validateDelta(result,(String)draft.get("body"));
    }
}
