package dev.novel.controller;

import dev.novel.dto.response.SystemResponses.AgentMetricsResponse;
import dev.novel.dto.response.SystemResponses.AgentResponse;
import dev.novel.service.AgentService;
import dev.novel.support.ResponseMapper;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentService agents;
    private final ResponseMapper responses;

    public AgentController(AgentService agents, ResponseMapper responses) {
        this.agents = agents;
        this.responses = responses;
    }

    @GetMapping
    public List<AgentResponse> catalogue() {
        return responses.list(agents.catalogue(), AgentResponse.class);
    }

    @GetMapping("/metrics")
    public List<AgentMetricsResponse> metrics(@RequestParam(required=false) String novelId) {
        return responses.list(agents.metrics(novelId), AgentMetricsResponse.class);
    }
}
