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
    public Set<String> tools() { return Set.of("read_memory","read_paragraph"); }
    private final AgentContextService context;
    private final ModelClient model;
    private final MemoryService memory;
    private final Json json;
    private final EvidenceMatcher evidence;
    public MemoryExtractionAgent(AgentContextService context,ModelClient model,MemoryService memory,Json json,EvidenceMatcher evidence) { this.context=context;this.model=model;this.memory=memory;this.json=json;this.evidence=evidence; }
    public Set<String> kinds() { return Set.of("MEMORY","MEMORY_REPAIR"); }
    public String purpose() { return "根据已审核正文整理人物、规则、伏笔、支线等 Markdown 主题记忆，并记录来源"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles(); }
    public JsonNode execute(Map<String,Object> task) throws Exception {
        var draft=context.draft(task);
        String draftInput=json.write(Map.of("title",draft.get("title"),"paragraphs",evidence.paragraphs((String)draft.get("body"))));
        var payload=json.read(Store.string(task,"payload"));
        boolean repairing="MEMORY_REPAIR".equals(task.get("kind"));
        var repair=repairing?Map.of("instructions",payload.path("instructions"),"review",payload.path("review"),
                "pending",payload.path("delta").has("files")?memory.files().repairContext(Store.string(task,"novel_id"),Store.number(task,"memory_version"),payload.path("delta")):payload.path("delta")):Map.of();
        var result=model.call(task,AgentInstructions.SYSTEM+purpose(),
                "输出 {summary:string,files:[{path:string,expectedHash:string,content:string,description:string,evidencePaths:[string]}]}。"+
                "summary 不超过600字。content 是完整 Markdown 文件，开头为 YAML front matter，必须包含 id、type、name 和 aliases；后接标题与主题正文。"+
                "新文件使用与主题对应的稳定标识。读取已有文件时只使用目录、搜索结果或上下文提供的真实路径，不要猜测路径。type 使用 character/world/hook/arc/stage/chapter/relation/issue；事件文件使用 chapter，current_state.md 使用 state，handoff_current.md 使用 handoff。"+
                "先读取已有当前主题文件；仅当前主题沿用原有 id、path 及未变化的有效内容。expectedHash 使用上下文 fileHashes 或 read_memory 返回的 hash；新文件填写空字符串。"+
                "editPolicy.readOnly=true 的文件只能读取，不在 files 中输出。editPolicy.kind=historical-source 或目录 legacy=true 表示历史来源文件，其中 memory-record 代码围栏及 JSON 只用于核对，不得复制到输出 content。"+
                "历史来源需要更新时，把仍然有效的事实整理到对应的当前主题；已存在当前主题时更新它，新主题使用独立的稳定 id 和 path，不能复用历史来源文件的 id 或 path。"+
                "人物和伏笔应按实体维护当前主题，保留来源文件链接、原始章节与获知顺序；事实、推测、计划及已解除状态分别表述。完整历史来源由程序保留。"+
                "每章必须更新 current_state.md 和 handoff_current.md；每个文件最多16000个UTF-8字节，这两个短文件最多6000字节。"+
                "MEMORY.md、各目录 INDEX.md 和 chapters/ 文件由程序维护，不在 files 中输出。每项 description 说明本章变化与影响，最多800字符。"+
                "evidencePaths 引用本章实际支持修改的 /paragraphs/p0 等路径；在正文中记录章节、revision、片段标识及事件来源。不得编造引用。"+
                "物品转移、关系和认知变化要同步更新相关人物文件；世界事实、人物推测和未来计划分别表达。历史事件通过章节记录追溯。"+
                "人物关系另外维护 relations/ 下的 Markdown 文件，包含 story-relations 代码围栏，内容为 JSON 数组；每项包含 subject、predicate、object、detail、evidence 字符串和 deleted 布尔值。"+
                "subject、object 使用稳定的人物名称；predicate 使用稳定关系类型。每条关系只在一个关系文件维护，解除关系保留该项并设置 deleted=true；evidence 记录正文证据。人物文件使用链接引用关系文件。"+
                "伏笔文件明确记录 OPEN/ADVANCING/RESOLVED/CANCELLED 状态，处理计划有指定章节时记录 dueChapter 与来源，description 保留状态和计划关键词供目录搜索。"+
                (repairing?"本次修正保持正文与大纲。files 是修正后完整的候选集合，将整体替换原候选列表。读取原候选，保留仍然有效的全部修改，始终包含 current_state.md 和 handoff_current.md。"+
                        "原候选若指向只读历史路径，需将有效内容整理到对应当前主题，并从完整候选集合中排除历史路径；新主题使用独立 id 和 path，expectedHash 为空。"+
                        "使用 read_candidate_memory 读取原候选正文；原候选只是待审核提案，不表示其目标路径允许写入。修正要求="+json.write(repair):"")+
                "上下文="+context.context(task,contextFiles(),draftInput+json.write(repair))+"；当前正文revision="+draft.get("revision")+"；正文="+draftInput);
        var normalized=memory.validateDelta(result,(String)draft.get("body"));
        return normalized;
    }
}
