package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.agent.validation.AgentOutputReader;
import dev.novel.dto.AgentOutputs;
import dev.novel.repository.Store;
import dev.novel.support.EvidenceMatcher;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MemoryService {
    private final Store store;
    private final Json json;
    private final AgentOutputReader outputs;
    private final EvidenceMatcher evidence;
    private final MemoryFileService files;
    public MemoryService(Store store,Json json,AgentOutputReader outputs,EvidenceMatcher evidence,MemoryFileService files) {
        this.store=store;this.json=json;this.outputs=outputs;this.evidence=evidence;this.files=files;
    }
    public MemoryFileService files() { return files; }
    public List<Map<String,Object>> snapshot(String novel,long version) {
        store.novel(novel);
        return files.snapshot(novel,version);
    }
    public Object planningContext(Map<String,Object> novel) {
        String id=Store.string(novel,"id");long version=Store.number(novel,"memory_version");
        return Map.of("MEMORY.md",files.readFull(id,version,"MEMORY.md"),"handoff_current.md",files.readFull(id,version,"handoff_current.md"));
    }
    public JsonNode validateDelta(JsonNode delta,String body) {
        if(delta.has("files")) return files.validate(delta,body);
        // 历史章节仍可读取并提交其原有记录，正式新任务使用 Markdown 文件修改。
        JsonNode normalized=delta.deepCopy();
        var paragraphs=evidence.paragraphs(body);
        for(String section:List.of("facts","relations")) for(var item:normalized.path(section)) {
            if(item.has("evidencePath")) {
                String path=item.path("evidencePath").asText();
                String selected=null;
                for(var paragraph:paragraphs.entrySet()) if(path.equals("/paragraphs/"+paragraph.getKey())) selected=paragraph.getValue();
                if(selected==null) throw new IllegalArgumentException("记忆证据的正文段落不存在："+path);
                ((ObjectNode)item).put("evidence",selected);((ObjectNode)item).remove("evidencePath");
            }
        }
        var output=outputs.read(normalized,AgentOutputs.MemoryDelta.class);
        if(output.facts().size()+output.relations().size()>40) throw new IllegalArgumentException("单章记忆变更最多40条");
        var keys=new HashSet<String>();var source=evidence.source(body);
        for(int index=0;index<output.facts().size();index++) {
            var fact=output.facts().get(index);
            if(!keys.add(fact.key())) throw new IllegalArgumentException("记忆 key 重复");
            ((ObjectNode)normalized.path("facts").get(index)).put("evidence",source.require(fact.evidence(),"第"+(index+1)+"条事实（"+fact.key()+"）"));
        }
        for(int index=0;index<output.relations().size();index++) {
            var relation=output.relations().get(index);
            String key="relation:"+relation.subject()+":"+relation.predicate()+":"+relation.object();
            if(key.length()>350 || !keys.add(key)) throw new IllegalArgumentException("关系 key 重复或过长");
            ((ObjectNode)normalized.path("relations").get(index)).put("evidence",source.require(relation.evidence(),"第"+(index+1)+"条关系（"+key+"）"));
        }
        return normalized;
    }
    public void commit(String novel,int chapter,long version,JsonNode delta) {
        store.transaction(()-> {
            store.lockNovel(novel);
            var draft=store.list("chapters.find",novel,chapter);
            files.commit(novel,chapter,draft.isEmpty()?0:Store.integer(draft.get(0),"revision"),version,delta);
            return null;
        });
    }
}
