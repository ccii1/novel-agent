package dev.novel.service;

import dev.novel.repository.mapper.ObservationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class TaskProgressService {
    private final ObservationMapper observations;
    private static final Map<String,String> STAGES=Map.ofEntries(
            Map.entry("ENQUEUED","已保存任务，等待执行"),Map.entry("RUNNING","开始执行任务"),
            Map.entry("AGENT_STARTED","正在准备 Agent 上下文"),Map.entry("RESEARCH_INPUT_READY","选题资料已准备完成"),
            Map.entry("AGENTSCOPE_REASONING_STARTED","正在准备模型请求"),Map.entry("MODEL_REQUEST_SENT","等待模型完整响应"),
            Map.entry("MODEL_RESPONSE_RECEIVED","已收到模型响应，正在处理"),Map.entry("MODEL_USAGE_RECORDED","已记录用量，正在检查输出"),
            Map.entry("RESEARCH_OUTPUT_VALIDATED","选题输出检查通过，正在保存"),Map.entry("AGENT_FINISHED","Agent 已完成，正在保存业务结果"),
            Map.entry("RATE_WAIT","等待模型调用额度"));
    public TaskProgressService(ObservationMapper observations) { this.observations=observations; }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> progress(String id) {
        var task=observations.progress(id);
        if(task==null) throw new NoSuchElementException("任务记录不存在");
        var events=observations.progressEvents(id);
        String last=events.isEmpty()?"":Objects.toString(events.get(0).get("type"),"");
        String status=task.get("status").toString();
        String stage=switch(status) {
            case "DONE" -> "回复已生成并保存";
            case "ERROR" -> "执行失败，请查看错误和调用记录";
            case "CANCELLED" -> "任务已取消";
            case "READY" -> "输出已保存，等待业务流程继续";
            case "WAITING" -> last.equals("RATE_WAIT")?STAGES.get(last):"任务已保存，等待执行";
            default -> "IN_FLIGHT".equals(task.get("call_status"))?STAGES.get("MODEL_REQUEST_SENT"):
                    STAGES.getOrDefault(last,"正在执行任务");
        };
        return Map.of("task",task,"stage",stage,"events",events,"pollAfterMs",5000);
    }
}
