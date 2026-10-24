package dev.novel.integration.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.agent.runtime.AgentRuntimeFactory;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.service.BudgetService;
import dev.novel.service.SkillService;
import dev.novel.support.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class ModelClient {
    private final Json json;
    private final BudgetService budget;
    private final int maxOutput,maxContext;
    private final ModelCatalog catalog;
    private final SkillService skills;
    private final AgentScopeExecutor executor;
    private final ModelRateLimiter limiter;
    private final ModelCallMapper calls;
    private final Store store;
    private final AgentRuntimeFactory runtimes;
    public ModelClient(Json json,BudgetService budget,@Value("${novel.model.max-output-tokens}") int maxOutput,
            @Value("${novel.max-context-bytes}") int maxContext,ModelCatalog catalog,SkillService skills,
            AgentScopeExecutor executor,ModelRateLimiter limiter,ModelCallMapper calls,Store store,AgentRuntimeFactory runtimes) {
        this.json=json;this.budget=budget;this.maxOutput=maxOutput;this.maxContext=maxContext;this.catalog=catalog;
        this.skills=skills;this.executor=executor;this.limiter=limiter;this.calls=calls;this.store=store;this.runtimes=runtimes;
    }
    public boolean configured() { return catalog.resolve(null,"","PLAN").ready(); }
    public void validateCheckpoint(Map<String,Object> task) {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        var previous=calls.checkpoint(Store.string(task,"id"));
        if(previous==null || !"COMPLETE".equals(previous.status()) || !skill.fingerprint().equals(previous.skillFingerprint()))
            throw new IllegalStateException("任务结果缺少当前技能版本的调用记录，请审核后重新执行");
    }
    public JsonNode call(Map<String,Object> task,String system,String prompt) throws Exception {
        var skill=skills.load(Store.string(task,"kind"),Store.integer(task,"chapter_no"));
        var previous=calls.checkpoint(Store.string(task,"id"));
        if(previous!=null) {
            // 只复用完整最终响应，并核对技能指纹；中间工具响应不能成为任务结果。
            validateCheckpoint(task);
            if(previous.actual().compareTo(previous.reserved())>0) throw new IllegalStateException("已保存调用超过预留费用，请核对后重新执行");
            return json.read(previous.response());
        }
        var selected=catalog.forTask(task);
        if(!selected.ready()) throw new IllegalStateException("请配置模型服务、模型名称和计费方式");
        var spec=runtimes.spec(Store.string(task,"kind"));
        var accounting=new AgentCallAccounting(task,selected,skill,store,calls,budget,limiter,json,maxContext,maxOutput,spec.maxRequests(),executor.limits(task));
        try {
            String result=executor.execute(task,selected,system+"\n"+skill.instruction()+
                    "\n只能使用本角色已注册的工具；资料不足时查询，已有资料充分时直接输出。先核对 _loadedMemory，完整提供的文件无需重复读取；路径已知时优先 read_memory_batch。工具结果的 _toolBudget 显示剩余字节和模型请求次数，保留最终输出所需的一次请求。必要资料未读全时明确报告缺失，不得编造事实。工具仅提供当前任务范围的数据，正式变更等待后端审批。",
                    prompt,maxOutput,accounting::before,accounting::after);
            accounting.complete(result);
            return json.read(result);
        } catch(Exception failure) {
            accounting.failed(failure);
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof BudgetService.RateLimited limited) throw limited;
                if(cause instanceof BudgetService.BudgetExceeded exceeded) throw exceeded;
            }
            throw failure;
        }
    }
}
