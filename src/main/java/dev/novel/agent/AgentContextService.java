package dev.novel.agent;

import dev.novel.repository.Store;
import dev.novel.service.TruthDocumentService;
import dev.novel.service.SkillService;
import dev.novel.support.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgentContextService {
    private final Store store;
    private final TruthDocumentService truth;
    private final Json json;
    private final ContextBudget budget;
    private final SkillService skills;
    private final int maxContext;

    public AgentContextService(Store store, TruthDocumentService truth, Json json, ContextBudget budget, SkillService skills,
                               @Value("${novel.max-context-bytes}") int maxContext) {
        this.store = store;
        this.truth = truth;
        this.json = json;
        this.budget = budget;
        this.skills = skills;
        this.maxContext = maxContext;
    }

    public Map<String, Object> novel(Map<String, Object> task) {
        var novel = store.novel(Store.string(task, "novel_id"));
        if (task.get("user_id") != null && !Objects.equals(task.get("user_id"), novel.get("user_id")))
            throw new IllegalStateException("Agent 任务与作品的所属用户不一致");
        if (Store.number(task, "memory_version") != Store.number(novel, "memory_version"))
            throw new IllegalStateException("Agent 任务的记忆版本已变化");
        return novel;
    }

    public String context(Map<String, Object> task, List<String> files) {
        return context(task, files, "");
    }

    public String context(Map<String, Object> task, List<String> files, String additionalInput) {
        var novel = novel(task);
        boolean preloadTopics = !Set.of("MEMORY", "MEMORY_REPAIR", "CHECK_MEMORY").contains(task.get("kind"));
        var documents = truth.prompt(novel, Store.integer(task, "chapter_no"), files, preloadTopics);
        boolean pending = Store.string(novel, "plan").isBlank() || "PLAN".equals(task.get("kind"))
                || Set.of("PLANNING", "AWAITING_PLAN").contains(Store.string(novel, "status"));
        documents.put("_workflow", Map.of("source", "DATABASE_WORKFLOW", "status", Store.string(novel, "status"),
                "stageNo", novel.get("stage_no"), "taskKind", task.get("kind"), "planStatus", pending ? "PENDING_APPROVAL" : "APPROVED"));
        if (Set.of("WRITE", "REWRITE").contains(task.get("kind"))) {
            var previous = store.list("chapters.previous", task.get("novel_id"), Store.integer(task, "chapter_no") - 1);
            if (!previous.isEmpty()) {
                String ending = Store.string(previous.get(0), "body");
                int count = ending.codePointCount(0, ending.length());
                documents.put("previousEnding", ending.substring(ending.offsetByCodePoints(0, Math.max(0, count - 1500))));
            }
        }
        return pack(task, documents, additionalInput);
    }

    public String pack(Map<String, Object> task, Map<String, Object> documents, String additionalInput) {
        String kind = Store.string(task, "kind");
        int chapter = Store.integer(task, "chapter_no");
        // 初始资料优先使用可用空间，工具结果按每次实际请求的剩余空间限制。
        int available = maxContext - budget.bytes(skills.load(kind, chapter).instruction()) - 12000 - 4096 - budget.bytes(additionalInput);
        if (available < 1024)
            throw new IllegalStateException("必需正文、任务资料与固定技能超过当前上下文预算，尚未请求模型");
        boolean fullPlan = Set.of("PLAN", "PLAN_CHANGE", "COORDINATE").contains(kind);
        var packed = budget.pack(documents, available, fullPlan, chapter);
        int omitted = 0;
        for (var count : packed.path("_contextBudget").path("omittedRecords")) omitted += count.asInt();
        store.event((String) task.get("novel_id"), Store.string(task, "id"), "CONTEXT_PACKED",
                json.write(Map.of("contextBytes", budget.bytes(packed), "limitBytes", available, "omittedRecords", omitted)), (String) task.get("execution_id"));
        return json.stable(packed);
    }

    public Map<String, Object> draft(Map<String, Object> task) {
        var draft = store.one("chapters.find", task.get("novel_id"), task.get("chapter_no"));
        if (Store.integer(draft, "revision") != Store.integer(task, "revision"))
            throw new IllegalStateException("Agent 任务的正文修订版本已变化");
        return draft;
    }

    public String legacyReviewInput(Map<String, Object> task) {
        return json.stable(Map.of("draft", draft(task), "reviews", store.list("tasks.reviews", task.get("novel_id"),
                task.get("chapter_no"), task.get("revision"), task.get("memory_version"))));
    }
}
