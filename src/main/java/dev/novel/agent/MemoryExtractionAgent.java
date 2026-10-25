package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.integration.model.ModelClient;
import dev.novel.service.MemoryService;
import dev.novel.service.MemoryPatchService;
import dev.novel.support.Json;
import dev.novel.repository.Store;
import dev.novel.support.EvidenceMatcher;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MemoryExtractionAgent implements NovelAgent {
    public Set<String> tools() {
        return Set.of("read_memory", "read_paragraph");
    }

    private final AgentContextService context;
    private final ModelClient model;
    private final MemoryService memory;
    private final MemoryPatchService patches;
    private final Json json;
    private final EvidenceMatcher evidence;

    public MemoryExtractionAgent(AgentContextService context, ModelClient model, MemoryService memory, MemoryPatchService patches, Json json, EvidenceMatcher evidence) {
        this.context = context;
        this.model = model;
        this.memory = memory;
        this.patches = patches;
        this.json = json;
        this.evidence = evidence;
    }

    public Set<String> kinds() {
        return Set.of("MEMORY", "MEMORY_REPAIR", "CHECK_MEMORY");
    }

    public String purpose() {
        return "提取已审核正文产生的记忆变化，由程序应用局部修改并校验文件";
    }

    public List<String> contextFiles() {
        return List.of("current_state.md", "handoff_current.md");
    }

    public JsonNode execute(Map<String, Object> task) throws Exception {
        var draft = context.draft(task);
        String body = Store.string(draft, "body"), novel = Store.string(task, "novel_id");
        long version = Store.number(task, "memory_version");
        var payload = json.read(Store.string(task, "payload"));
        // 已保存的候选和人工编辑使用本地校验，不产生审核模型调用。
        if ("CHECK_MEMORY".equals(task.get("kind"))) {
            var delta = memory.validateDelta(payload.has("delta") ? payload.path("delta") : payload, body);
            if (delta.has("files")) memory.files().validateBase(novel, version, delta);
            return delta;
        }
        String input = json.write(Map.of("title", draft.get("title"), "paragraphs", evidence.paragraphs(body)));
        var instructions = Map.of("instructions", payload.path("instructions"), "previousIssues", payload.path("review").path("issues"),
                "previousError", payload.path("retryFailure"));
        var result = model.call(task, AgentInstructions.SYSTEM + purpose(),
                "只提取本章已发生且影响后续写作的变化：地点、伤势、物品转移、人物知情、能力消耗和伏笔进展。每项事实只记录一次，其他主题使用链接。" +
                        "输出 {summary:string,patches:[{path:string,expectedHash:string,content:null,edits:[{oldText:string,newText:string}],description:string,evidencePaths:[string]}]}。" +
                        "summary最多600字，patches最多12项，总输出最多32000个UTF-8字节。不要重新讲述正文，不记录本章没有变化的事实。" +
                        "读取需要更新的当前主题，使用工具返回的真实 path 和 hash。已有文件 content 必须为 null；oldText 是文件中唯一匹配的连续原文，newText 只修改对应状态，包含必要章节来源。所有 edits 基于同一份原文且不能重叠。" +
                        "未修改内容由程序逐字保留，禁止整篇替换人物文件。新增信息可通过替换包含标题的唯一片段插入。" +
                        "新主题 expectedHash为空，edits=[]，content为完整Markdown，YAML front matter含独立稳定 id、type、name、aliases。目录类型为 characters/character、world/world、hooks/hook、arcs/arc、relations/relation、issues/issue。" +
                        "历史来源 legacy=true 或 readOnly=true 仅供读取；需要更新时创建独立当前主题，保留来源链接。不得复用历史 id/path。" +
                        "关系在同一主题的 story-relations 代码围栏中使用JSON数组，条目包含subject、predicate、object、detail、evidence和deleted；沿用现有关系类型，不重复建立同一关系。" +
                        "每章更新 current_state.md 和 handoff_current.md；仅维护当前状态和近期交接，不复制人物档案。MEMORY.md、INDEX.md、chapters/由程序生成。" +
                        "evidencePaths必须指向正文实际支持变化的 /paragraphs/p0 等路径；推测与事实分别表述，不根据未提及推断痊愈、恢复次数或解除威胁。" +
                        "修正资料=" + json.write(instructions) + "；上下文=" + context.context(task, contextFiles(), input + json.write(instructions)) +
                        "；正文revision=" + draft.get("revision") + "；正文=" + input);
        return patches.materialize(novel, version, result, body);
    }
}
