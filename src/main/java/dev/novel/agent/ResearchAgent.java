package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.repository.Store;
import dev.novel.service.ResearchService;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ResearchAgent implements NovelAgent {
    private final ResearchService research;
    private final Json json;

    public ResearchAgent(ResearchService research, Json json) {
        this.research = research;
        this.json = json;
    }

    public Set<String> kinds() {
        return Set.of("RESEARCH", "RESEARCH_CHAT");
    }

    public String purpose() {
        return "依据用户想法与公开资料提出和讨论原创选题";
    }

    public List<String> contextFiles() {
        return List.of();
    }

    public JsonNode execute(Map<String, Object> task) throws Exception {
        return research.execute(task, json.read(Store.string(task, "payload")), AgentInstructions.SYSTEM);
    }
}
