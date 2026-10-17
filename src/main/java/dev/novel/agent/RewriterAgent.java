package dev.novel.agent;

import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RewriterAgent extends ChapterAuthorAgent {
    public RewriterAgent(AgentContextService context,ModelClient model,Json json,AgentOutputReader outputs) { super(context,model,json,outputs); }
    public Set<String> kinds() { return Set.of("REWRITE"); }
    public String purpose() { return "依据统一修改计划修订正文并保留已通过内容"; }
    protected String instructions(Map<String,Object> task) {
        var novel=context.novel(task);
        var source=new LinkedHashMap<>(task);
        // REWRITE 指向待生成版本，原正文使用上一轮已经保存的版本。
        source.put("revision",((Number)task.get("revision")).intValue()-1);
        return "；原章节="+json.write(context.draft(source))+"；修改计划="+novel.get("reflection");
    }
}
