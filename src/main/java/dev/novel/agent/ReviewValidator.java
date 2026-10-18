package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonPointer;
import dev.novel.dto.AgentOutputs;
import dev.novel.support.EvidenceMatcher;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReviewValidator {
    private final AgentOutputReader outputs;
    private final EvidenceMatcher evidence;
    public ReviewValidator(AgentOutputReader outputs,EvidenceMatcher evidence) { this.outputs=outputs;this.evidence=evidence; }
    public JsonNode validate(JsonNode review,String body) { return validate(review,body,null); }
    public Map<String,String> paragraphs(String body) {
        return evidence.paragraphs(body);
    }
    public JsonNode validate(JsonNode review,String body,JsonNode memory) {
        if(review==null || !review.isObject() || !review.path("issues").isArray())
            throw new IllegalArgumentException("检查报告必须包含 issues 数组");
        JsonNode normalized=review.deepCopy();
        var bodySource=evidence.source(body);
        Map<String,String> bodyFields=new LinkedHashMap<>();
        paragraphs(body).forEach((key,value)->bodyFields.put("/paragraphs/"+key,value));
        Map<String,String> memorySources=new LinkedHashMap<>();
        if(memory!=null) {
            memorySources.put("/summary",memory.path("summary").asText());
            for(String section:java.util.List.of("facts","relations"))
                for(int index=0;index<memory.path(section).size();index++)
                    for(String field:java.util.List.of("key","name","detail","evidence","subject","predicate","object")) {
                        String path="/"+section+"/"+index+"/"+field;
                        if(memory.at(path).isTextual()) memorySources.put(path,memory.at(path).asText());
                    }
        }
        for(int index=0;index<normalized.path("issues").size();index++) {
            if(!(normalized.path("issues").get(index) instanceof ObjectNode node)) throw new IllegalArgumentException("检查问题必须为对象");
            String requested=node.path("quoteSource").asText("");
            if(!java.util.Set.of("","BODY","MEMORY").contains(requested)) throw new IllegalArgumentException("未知引用来源");
            String path=node.path("quotePath").asText("");
            if(!path.isBlank()) {
                if(requested.equals("MEMORY") && memory!=null) {
                    var pointer=JsonPointer.compile(path);
                    String root=pointer.getMatchingProperty();
                    if(root!=null && java.util.Set.of("summary","facts","relations").contains(root)) {
                        var selected=memory.at(pointer);
                        if(!selected.isMissingNode()) {
                            String text=selected.isTextual()?selected.textValue():null;
                            node.put("quote",text!=null && evidence.source(text).find(text)!=null?text:path+" = "+selected);
                            node.set("quoteValue",selected.deepCopy());
                            continue;
                        }
                    }
                }
                String selected=requested.equals("BODY")?bodyFields.get(path):null;
                if(selected==null) throw new IllegalArgumentException("第"+(index+1)+"条问题引用的来源字段不存在："+requested+" "+path);
                node.put("quote",evidence.source(selected).require(selected,"第"+(index+1)+"条问题"));
                continue;
            }
            String quote=node.path("quote").asText("");
            String matched=requested.equals("MEMORY")?null:bodySource.find(quote);
            if(matched!=null) { node.put("quote",matched);node.put("quoteSource","BODY"); }
            else if(!requested.equals("BODY")) {
                for(var entry:memorySources.entrySet()) {
                    matched=evidence.source(entry.getValue()).find(quote);
                    if(matched!=null) { node.put("quote",matched);node.put("quoteSource","MEMORY");node.put("quotePath",entry.getKey());break; }
                }
            }
            if(matched==null) throw new IllegalArgumentException("检查输出格式错误：第"+(index+1)+
                    "条问题的 quote 无法连续匹配正文或指定的待审核记忆："+node.path("quote")+"；模型响应已保存");
        }
        var output=outputs.read(normalized,AgentOutputs.Review.class);
        if(output.approved()!=output.issues().isEmpty()) throw new IllegalArgumentException("检查结论与问题列表不一致");
        return normalized;
    }
}
