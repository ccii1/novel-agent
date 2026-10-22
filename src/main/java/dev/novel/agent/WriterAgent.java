package dev.novel.agent;

import dev.novel.agent.validation.AgentOutputReader;

import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WriterAgent extends ChapterAuthorAgent {
    public WriterAgent(AgentContextService context,ModelClient model,Json json,AgentOutputReader outputs) { super(context,model,json,outputs); }
    public Set<String> kinds() { return Set.of("WRITE"); }
    public String purpose() { return "依据已批准章节目标创作正文"; }
    protected String instructions(Map<String,Object> task) { return ""; }
}
