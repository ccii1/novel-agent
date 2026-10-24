package dev.novel.support;

import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class TaskLogFormatter {
    private static final Set<String> DETAILS=Set.of("RUNNING","AGENT_FINISHED",
            "AGENTSCOPE_REASONING_STARTED","AGENTSCOPE_REASONING_FINISHED");
    private final Json json;

    public TaskLogFormatter(Json json) { this.json=json; }

    public boolean detail(String type) { return DETAILS.contains(type); }

    public String label(Map<String,Object> task,String id,String novel) {
        String reference=id==null?"": " · 任务"+id.substring(0,Math.min(8,id.length()));
        if(task==null) return novel==null?"[任务调度]":"[作品"+novel.substring(0,Math.min(8,novel.length()))+"]";
        String title=Objects.toString(task.get("title"),"");
        String book=title.isBlank()?"": "《"+line(title)+"》 · ";
        int chapter=((Number)task.get("chapter_no")).intValue();
        String kind=Objects.toString(task.get("kind"),"");
        String position=chapter>0 && !kind.equals("PLAN")?"第"+chapter+"章 · ":"";
        return "["+book+position+role(kind)+reference+"]";
    }

    public String event(String type,String message,String kind) {
        return switch(type) {
            case "ENQUEUED" -> "任务已排队，等待执行";
            case "AGENT_STARTED" -> "开始执行，正在准备所需资料";
            case "MODEL_REQUEST_SENT" -> {
                var data=json.read(message);
                String billing="unmetered".equals(data.path("billingMode").asText())?"本次免费":
                        "预留费用上限 "+data.path("reserved").asText()+" 元";
                  String effort=data.path("reasoningEffort").asText("");
                yield "请求模型 "+line(data.path("provider").asText())+" / "+line(data.path("model").asText())+
                          "，思考强度 "+(effort.isBlank()?"服务默认":line(effort))+"，"+billing+"，等待完整回复";
            }
            case "MODEL_RESPONSE_RECEIVED" -> {
                var data=json.read(message);
                yield "收到模型 HTTP 响应，耗时 "+seconds(data.path("latencyMs").asLong())+
                        "，HTTP "+data.path("httpStatus").asInt()+"，正在检查响应";
            }
            case "MODEL_USAGE_RECORDED" -> {
                var data=json.read(message);
                String cached=data.hasNonNull("cachedInputTokens")?data.path("cachedInputTokens").asText():"未报告";
                String billing="unmetered".equals(data.path("billingMode").asText())?"本次免费":
                        "配置单价估算费用 "+data.path("estimatedCost").asText()+" 元";
                yield "用量已保存：输入 "+data.path("inputTokens").asLong()+" token，输出 "+
                        data.path("outputTokens").asLong()+" token，缓存命中 "+cached+"；"+billing;
            }
            case "RESEARCH_INPUT_READY" -> "选题资料已准备完成，公开资料 "+json.read(message).path("sourceCount").asInt()+" 份";
            case "RESEARCH_OUTPUT_VALIDATED" -> "选题输出检查通过，方案 "+json.read(message).path("ideaCount").asInt()+" 个";
            case "DONE" -> kind.equals("PLAN")?"阶段大纲已保存，等待人工审核":"任务结果已保存";
            case "REVIEW_APPROVED", "REVIEW_REJECTED", "MEMORY_REPAIR_STARTED", "MEMORY_REPAIR_APPROVED" -> message;
            case "CHAPTER_ACCEPTED" -> "章节检查通过，正文和记忆已保存";
            case "ERROR" -> "执行失败："+line(message);
            case "RATE_WAIT" -> "调用频率达到限制，任务已重新排队";
            case "RECOVERY" -> "任务恢复检查完成，结果不确定的调用等待人工核对";
            case "BILLING_RECONCILED" -> "调用费用已人工核对并保存";
            case "MODEL_SELECTED" -> "作品的模型选择已保存";
            case "PUBLICATION_MARKED" -> line(message);
            case "PLAN_APPROVED" -> "阶段大纲已批准，继续生成章节";
            case "PLAN_CHANGE_READY", "PLAN_CHANGE_APPLIED" -> line(message);
            case "COORDINATION_APPROVED" -> "委派建议已确认，已创建对应 Agent 的持久化任务";
            case "CONTEXT_PACKED" -> {
                var data=json.read(message);
                yield "已整理角色资料："+data.path("contextBytes").asInt()+" / "+data.path("limitBytes").asInt()+
                        " 字节，省略检索记录 "+data.path("omittedRecords").asInt()+" 条，可按需查询";
            }
            case "AGENT_TOOL_USED" -> {
                var data=json.read(message);
                yield "Agent 调用工具："+data.path("tool").asText()+(data.has("responseBytes")?
                        "，返回 "+data.path("responseBytes").asInt()+" 字节，剩余工具预算 "+data.path("remainingToolBytes").asInt()+" 字节":"");
            }
            case "AGENT_RUNTIME_BUILT" -> "已构建独立 Agent："+json.read(message).path("name").asText();
            case "MODEL_TURN_STARTED" -> {
                var data=json.read(message);
                yield "开始第"+data.path("turn").asInt()+"次模型请求，上限"+data.path("limit").asInt()+"次"+
                          (data.has("contextBytes")?"；上下文 "+data.path("contextBytes").asInt()+" / "+data.path("contextLimitBytes").asInt()+" 字节":"")+
                          (data.has("requestTimeoutSeconds")?"；单次等待上限 "+data.path("requestTimeoutSeconds").asLong()+" 秒":"");
            }
            case "STAGE_APPROVED" -> "已批准进入下一阶段，开始规划";
            case "PAUSED" -> "作品已暂停，已发出的模型调用仍会结算并保存结果";
            case "RESUMED" -> "作品已恢复执行";
            case "REVISION_APPROVED" -> "修改意见已批准，继续执行";
            case "RETRY_APPROVED" -> "已批准重新执行，可能产生新的调用费用";
            case "EDIT_APPROVED" -> "章节编辑已批准，重新规划和生成";
            default -> "已记录事件 "+line(type);
        };
    }

    public String progress(String stage,Object elapsed,Object modelElapsed) {
        return line(stage)+"；任务已用 "+seconds(elapsed)+
                (modelElapsed==null?"":"，模型调用已用 "+seconds(modelElapsed));
    }

    private String seconds(Object value) {
        return value instanceof Number number?String.format(Locale.ROOT,"%.1f 秒",number.doubleValue()/1000):"时间未记录";
    }

    private String line(String value) { return Objects.toString(value,"").replace('\r',' ').replace('\n',' '); }

    private String role(String kind) {
        return switch(kind) {
            case "PLAN" -> "阶段大纲规划";
            case "PLAN_CHANGE" -> "局部大纲修改";
            case "COORDINATE" -> "创作协调";
            case "WRITE" -> "正文创作";
            case "REWRITE" -> "正文修改";
            case "CHECK_CHARACTER" -> "人物一致性检查";
            case "CHECK_PLOT" -> "剧情与伏笔检查";
            case "CHECK_PACING" -> "节奏检查";
            case "CHECK_STYLE" -> "文风检查";
            case "CHECK_CHAPTER" -> "正文联合审核";
            case "MEMORY_REPAIR" -> "记忆修正";
            case "REFLECT" -> "修改方案分析";
            case "MEMORY" -> "记忆提取";
            case "CHECK_MEMORY" -> "记忆检查";
            case "RESEARCH" -> "选题研究";
            case "RESEARCH_CHAT" -> "选题讨论";
            default -> kind;
        };
    }
}
