package dev.novel.agent;

import dev.novel.agent.validation.ReviewValidator;

import dev.novel.integration.model.ModelClient;
import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.service.MemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryCheckAgent extends ReviewAgent {
    private final MemoryService memory;
    public MemoryCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator,MemoryService memory) {
        super(context,model,json,validator);this.memory=memory;
    }
    public Set<String> kinds() { return Set.of("CHECK_MEMORY"); }
    public String purpose() { return "审核 Markdown 修改的正文依据、历史状态及关联文件，并检查本章重要变化是否遗漏"; }
    public List<String> contextFiles() { return List.of("MEMORY.md","story_bible.md","outline.md","book_rules.md"); }
    @Override public JsonNode execute(Map<String,Object> task) throws Exception {
        var payload=json.read(Store.string(task,"payload"));var delta=delta(task);
        if(!delta.has("files")) return super.execute(task);
        var draft=context.draft(task);String body=Store.string(draft,"body");
        boolean coverage="COVERAGE".equals(payload.path("reviewPhase").asText());
        boolean all="ALL".equals(payload.path("reviewPhase").asText());
        var indices=new ArrayList<Integer>();
        if(!coverage) payload.path("reviewGroups").path(payload.path("reviewIndex").asInt()).forEach(value->indices.add(value.asInt()));
        if(!coverage && indices.isEmpty()) throw new IllegalArgumentException("记忆审核分组为空");
        Object material=coverage?memory.files().coverage(delta):memory.files().reviewGroupInput(Store.string(task,"novel_id"),
                Store.number(task,"memory_version"),delta,indices,body);
        if(all) material=Map.of("fileReview",material,"coverage",memory.files().coverage(delta));
        String input=json.write(material);
        String paragraphs=coverage || all?json.write(validator().paragraphs(body)):"本次正文证据已在 evidence 中提供，需要其他片段时调用 read_paragraph。";
        var result=model.call(task,AgentInstructions.SYSTEM+"你负责独立审核 Markdown 记忆，不能修改正文或正式文件。",
                "输出 {approved:boolean,majorChange:boolean,issues:[{severity:string,quoteSource:string,quotePath:string,reason:string,suggestion:string}]}。"+
                "通过时 issues 必须为空；需要改变核心剧情、重要人物转变或关键伏笔答案时设置 majorChange=true。"+
                "问题引用正文时 quoteSource=BODY，quotePath=/paragraphs/p0；引用候选文件时 quoteSource=MEMORY，quotePath=/files/实际序号/content 或 /summary。"+
                "引用已发布文件时 quoteSource=PUBLISHED，quotePath 为工具返回的实际 Markdown 路径，并填写该文件中的连续原文 quote。"+
                "MEMORY 的序号仅指本次候选 files 数组，从0开始；正式目录或批量查询的序号不能用于 /files/序号/content。"+
                "引用文件内容时同时填写 quote，选择与问题直接相关且至少4个有效字符的连续原文，程序会核对该片段；不要重复引用完整文件。"+
                (coverage || all?"检查本章重要变化遗漏、摘要依据、文件修改依据、旧状态与跨文件一致性；核对物品双方状态与人物认知，原有有效信息应当保留。":"检查当前文件修改的原文依据、旧状态、实体、认知与关联主题，原有有效信息应当保留。")+
                "证据不足时继续使用文件和来源工具，不能编造矛盾。上下文="+context.context(task,contextFiles(),input+paragraphs)+
                "；审核资料="+input+"；正文="+paragraphs);
        return validateReview(task,result,body);
    }
    private JsonNode delta(Map<String,Object> task) {
        var payload=json.read(Store.string(task,"payload"));return payload.has("delta")?payload.get("delta"):payload;
    }
    protected String additionalInput(Map<String,Object> task,String body) {
        var pending=delta(task).deepCopy();
        for(String section:List.of("facts","relations")) for(var item:pending.path(section)) {
            var record=(com.fasterxml.jackson.databind.node.ObjectNode)item;
            record.remove("evidence");record.put("evidenceInBody",true);
        }
        return "；待检查记忆="+json.write(pending)+"；各项证据从本章正文核对，引用待审字段时仍使用原始 JSON Pointer。";
    }
    protected String additionalSchema() { return "记忆问题可以引用待审核记忆：issues 中填写 quoteSource=MEMORY 与实际存在的 JSON Pointer quotePath；"+
            "具体字段如 /summary、/facts/0/detail、/facts/0/active，跨字段问题可选择整条 /facts/0 或 /relations/0，遗漏问题可引用 /facts 或 /relations。"+
            "无需抄写 quote，由程序读取实际值。引用正文填写 quoteSource=BODY。人物怀疑与推断不能升级为确定事实。"; }
    protected JsonNode validateReview(Map<String,Object> task,JsonNode result,String body) {
        var published=new LinkedHashMap<String,String>();
        for(var issue:result.path("issues")) if("PUBLISHED".equals(issue.path("quoteSource").asText())) {
            String path=issue.path("quotePath").asText();
            published.computeIfAbsent(path,key->memory.files().readFull(Store.string(task,"novel_id"),Store.number(task,"memory_version"),key));
        }
        return validator().validate(result,body,delta(task),published);
    }
}
