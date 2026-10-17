package dev.novel.agent;

import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class PlotCheckAgent extends ReviewAgent {
    public PlotCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator) { super(context,model,json,validator); }
    public Set<String> kinds() { return Set.of("CHECK_PLOT"); }
    public String purpose() { return "时间线、因果关系、世界规则、伏笔埋设与回收、是否遵守批准的大纲"; }
}
