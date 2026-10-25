package dev.novel.agent;

import dev.novel.agent.validation.ChapterOutputValidator;

import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class WriterAgent extends ChapterAuthorAgent {
    public WriterAgent(AgentContextService context, ModelClient model, Json json, ChapterOutputValidator chapters) {
        super(context, model, json, chapters);
    }

    public Set<String> kinds() {
        return Set.of("WRITE", "REWRITE", "REFLECT");
    }

    public String purpose() {
        return "依据已批准章节目标创作正文，依据审核报告修改正文";
    }

    protected String instructions(Map<String, Object> task) {
        if (!"REWRITE".equals(task.get("kind"))) return "";
        var source = new java.util.LinkedHashMap<>(task);
        source.put("revision", ((Number) task.get("revision")).intValue() - 1);
        return "；原章节=" + json.stable(context.draft(source)) + "；修改计划=" + context.novel(task).get("reflection");
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode execute(Map<String, Object> task) throws Exception {
        if (!"REFLECT".equals(task.get("kind"))) return super.execute(task);
        return model.call(task, AgentInstructions.SYSTEM + purpose(),
                "根据当前正文与审核报告输出修改计划 {majorChange:boolean,instructions:string,conflicts:[string]}，不得自行执行重大变更。资料=" +
                        context.legacyReviewInput(task));
    }
}
