package dev.novel.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.repository.Store;
import dev.novel.service.TaskScheduler;
import dev.novel.support.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReviewOrchestrator {
    private final Store store;
    private final TaskScheduler scheduler;
    private final Json json;
    private final String mode;

    public ReviewOrchestrator(Store store, TaskScheduler scheduler, Json json, @Value("${novel.review-mode:combined}") String mode) {
        if (!Set.of("parallel", "combined").contains(mode))
            throw new IllegalArgumentException("review-mode 必须为 combined 或 parallel");
        this.store = store;
        this.scheduler = scheduler;
        this.json = json;
        this.mode = mode;
    }

    public record Round(List<String> expected, List<String> completed, boolean ready, boolean approved,
                        boolean majorChange, boolean planConflict) {
    }

    private List<String> expected(Map<String, Object> novel, int chapter, int revision) {
        // 已创建轮次沿用原审核模式，重启或修改配置不会改变正在等待的角色集合。
        var existing = store.list("tasks.chapterChecks", novel.get("id"), chapter, revision, novel.get("memory_version"));
        boolean combined = existing.stream().anyMatch(row -> "CHECK_CHAPTER".equals(row.get("kind")));
        boolean parallel = existing.stream().anyMatch(row -> AgentRoles.CHAPTER_CHECKS.contains(row.get("kind")));
        if (combined && parallel) throw new IllegalStateException("同一正文审核轮次存在两种审核方式");
        return combined || !parallel && mode.equals("combined") ? List.of("CHECK_CHAPTER") : AgentRoles.CHAPTER_CHECKS;
    }

    public void start(Map<String, Object> novel, int chapter, int revision) {
        var roles = expected(novel, chapter, revision);
        for (String role : roles) scheduler.schedule(novel, role, chapter, revision, Map.of("reviewRoles", roles));
    }

    public Round collect(Map<String, Object> novel, int chapter, int revision) {
        var roles = expected(novel, chapter, revision);
        var reports = new LinkedHashMap<String, JsonNode>();
        for (var row : store.list("tasks.reviews", novel.get("id"), chapter, revision, novel.get("memory_version"))) {
            String role = Store.string(row, "kind");
            if (!roles.contains(role) || reports.putIfAbsent(role, json.read(Store.string(row, "result"))) != null)
                throw new IllegalStateException("审核结果的角色重复或不属于本轮审核");
        }
        boolean ready = reports.keySet().containsAll(roles);
        return new Round(roles, List.copyOf(reports.keySet()), ready, ready && reports.values().stream().allMatch(report -> report.path("approved").asBoolean()),
                reports.values().stream().anyMatch(report -> report.path("majorChange").asBoolean()),
                reports.values().stream().anyMatch(report -> report.path("planConflict").asBoolean()));
    }
}
