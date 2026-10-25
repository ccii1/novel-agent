package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
public class ChapterMemoryTopics {
    private final MemoryMarkdown markdown;
    private final Json json;
    private final int limit;
    private final Cache<String, List<String>> aliases = Caffeine.newBuilder().maximumSize(2048).expireAfterAccess(Duration.ofMinutes(30)).build();

    public ChapterMemoryTopics(MemoryMarkdown markdown, Json json, @Value("${novel.memory-initial-topic-bytes:16000}") int limit) {
        if (limit < 2048) throw new IllegalArgumentException("本章主题预算至少为2048字节");
        this.markdown = markdown;
        this.json = json;
        this.limit = limit;
    }

    public Map<String, Object> resolve(MemoryFileService files, String novel, long version, Set<String> terms, List<Map<String, Object>> manifest) {
        var required = new LinkedHashMap<String, String>();
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        terms.forEach(term -> groups.put(term, new ArrayList<>()));
        for (var row : manifest) {
            String kind = Store.string(row, "kind");
            if (!Set.of("character", "hook", "legacy-character", "legacy-hook").contains(kind) || terms.isEmpty())
                continue;
            var names = new HashSet<>(List.of(Store.string(row, "entity_key"), Store.string(row, "title")));
            if (!kind.startsWith("legacy-")) {
                String path = Store.string(row, "path");
                names.addAll(aliases.get(novel + ":" + row.get("fingerprint"), key -> markdown.parse(files.readFull(novel, version, path)).aliases()));
            }
            for (String term : terms) if (names.contains(term)) groups.get(term).add(row);
        }
        var pending = new ArrayList<Map<String, Object>>();
        int matches = 0;
        for (var group : groups.entrySet()) {
            String term = group.getKey();
            var rows = group.getValue();
            rows.sort(Comparator.<Map<String, Object>, Boolean>comparing(row -> !term.equals(row.get("entity_key")))
                    .thenComparing(row -> Store.string(row, "kind").startsWith("legacy-"))
                    .thenComparing(Comparator.comparingInt((Map<String, Object> row) -> Store.integer(row, "chapter_no")).reversed()));
            var current = rows.stream().filter(row -> !Store.string(row, "kind").startsWith("legacy-")).toList();
            var exact = rows.stream().filter(row -> term.equals(row.get("entity_key"))).toList();
            Map<String, Object> chosen = exact.size() == 1 ? exact.get(0) : current.size() == 1 ? current.get(0) : null;
            if (chosen != null && !Store.string(chosen, "kind").startsWith("legacy-")) {
                String path = Store.string(chosen, "path"), text = files.readFull(novel, version, path);
                required.put(path, text);
                if (size(required, pending) > limit) required.remove(path);
            }
            for (var row : rows) {
                String path = Store.string(row, "path");
                if (required.containsKey(path)) continue;
                // 已有明确的当前主题时，历史来源通过主题和章节工具继续追溯。
                if (chosen != null && required.containsKey(chosen.get("path"))) continue;
                matches++;
                var reference = Map.<String, Object>of("term", term, "path", path, "title", row.get("title"), "id", row.get("entity_key"),
                        "hash", row.get("fingerprint"), "bytes", row.get("bytes"), "chapter", row.get("chapter_no"),
                        "legacy", Store.string(row, "kind").startsWith("legacy-"), "ambiguous", chosen == null && current.size() > 1);
                pending.add(reference);
                if (size(required, pending) > limit) pending.remove(pending.size() - 1);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        if (!required.isEmpty()) result.put("requiredTopics", required);
        if (!pending.isEmpty() || matches > 0)
            result.put("unresolvedTopics", Map.of("examples", pending, "total", matches, "hasMore", matches > pending.size(),
                    "instruction", "按 term 分组定位本章人物或伏笔，使用 read_memory_batch 读取所需路径；同名候选需要核对身份。legacy=true 表示只读历史来源，不得复制其 memory-record 附录；请更新普通当前主题，新主题使用独立 id 和 path。这里只列出预算内入口，未列出不表示不存在。"));
        return result;
    }

    private int size(Map<String, String> required, List<Map<String, Object>> pending) {
        return MemoryFileService.bytes(json.write(Map.of("requiredTopics", required, "examples", pending)));
    }
}
