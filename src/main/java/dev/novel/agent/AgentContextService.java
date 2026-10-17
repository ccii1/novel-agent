package dev.novel.agent;

import dev.novel.repository.Store;
import dev.novel.service.TruthDocumentService;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AgentContextService {
    private final Store store;
    private final TruthDocumentService truth;
    private final Json json;
    public AgentContextService(Store store,TruthDocumentService truth,Json json) { this.store=store;this.truth=truth;this.json=json; }
    public Map<String,Object> novel(Map<String,Object> task) {
        var novel=store.novel(Store.string(task,"novel_id"));
        if(Store.number(task,"memory_version")!=Store.number(novel,"memory_version"))
            throw new IllegalStateException("Agent 任务的记忆版本已变化");
        return novel;
    }
    public String context(Map<String,Object> task,List<String> files) {
        return json.write(truth.prompt(novel(task),Store.integer(task,"chapter_no"),files));
    }
    public Map<String,Object> draft(Map<String,Object> task) {
        var draft=store.one("chapters.find",task.get("novel_id"),task.get("chapter_no"));
        if(Store.integer(draft,"revision")!=Store.integer(task,"revision"))
            throw new IllegalStateException("Agent 任务的正文修订版本已变化");
        return draft;
    }
}
