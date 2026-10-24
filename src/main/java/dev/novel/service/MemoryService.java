package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.support.EvidenceMatcher;
import dev.novel.agent.validation.AgentOutputReader;
import dev.novel.integration.cache.MemoryProjectionCache;
import dev.novel.integration.graph.StoryGraph;
import dev.novel.dto.AgentOutputs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MemoryService {
    private final Store store;
    private final Json json;
    private final AgentOutputReader outputs;
    private final MemoryProjectionCache cache;
    private final EvidenceMatcher evidence;
    private final StoryGraph relationships;
    private final MemoryPromptProjection projection;
    private final MemoryFileService files;
    public MemoryService(Store store,Json json,AgentOutputReader outputs,MemoryProjectionCache cache,EvidenceMatcher evidence,StoryGraph relationships,MemoryPromptProjection projection,MemoryFileService files) {
        this.store=store;this.json=json;this.outputs=outputs;this.cache=cache;this.evidence=evidence;this.relationships=relationships;
        this.projection=projection;
        this.files=files;
    }
    public MemoryFileService files() { return files; }
    public List<Map<String,Object>> snapshot(String novel, long version) {
        store.novel(novel);
        return cache.snapshot(novel,version,()->files.snapshot(novel,version));
    }
    public Map<String,Object> graph(String novel, long version, List<String> characters, int depth) {
        if(depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        var selected=characters.stream().distinct().sorted().toList();
        String owner=Store.string(store.novel(novel),"user_id");
        return cache.graph(novel,version,json.write(Map.of("characters",selected,"depth",depth,"graphEngine",relationships.mode(),"schema",3)),
                ()->relationships.query(owner,novel,version,selected,depth,()->snapshot(novel,version)));
    }
    public Map<String,Object> contextData(Map<String,Object> novel,int chapter) {
        String id=(String)novel.get("id"); long version=Store.number(novel,"memory_version");
        var plan=Store.string(novel,"plan").isBlank()?json.read("{}"):json.read(Store.string(novel,"plan"));
        List<String> characters=new ArrayList<>();
        plan.path("chapters").forEach(item -> {
            if (item.path("number").asInt()==chapter) item.path("characters").forEach(c->characters.add(c.asText()));
        });
        List<Map<String,Object>> relevant=new ArrayList<>();
        var snapshot=snapshot(id,version);
        var relatedGraph=graph(id,version,characters,2);
        String terms=json.write(relatedGraph.get("nodes"));
        Set<String> requestedKeys=new HashSet<>();
        plan.path("chapters").forEach(item->{if(item.path("number").asInt()==chapter) item.path("foreshadowIds").forEach(key->requestedKeys.add(key.asText()));});
        List<Map.Entry<Integer,Map<String,Object>>> ranked=new ArrayList<>();
        // 评分依据当前人物、伏笔安排和近期章节；最多保留50条事实供本章使用。
        for (var row : snapshot) {
            var data=json.read((String)row.get("content"));
            if (data.path("deleted").asBoolean(false) || "RELATION".equals(row.get("kind"))) continue;
            int score=0;
            if("WORLD".equals(row.get("kind"))) score=30;
            if("FORESHADOW".equals(row.get("kind")) && data.path("active").asBoolean(false)) score=50;
            if("FORESHADOW".equals(row.get("kind")) && data.path("dueChapter").asInt(Integer.MAX_VALUE)<=chapter+Store.integer(novel,"stage_size")) score=90;
            for(var name:characters) if(data.toString().contains(name)) score=80;
            if(terms.contains(data.path("name").asText("\u0000"))) score=85;
            if(requestedKeys.contains(row.get("memory_key"))) score=100;
            if(Store.integer(row,"chapter_no")>=chapter-4) score=Math.max(score,40);
            if(score>0) ranked.add(Map.entry(score,Map.of("key",row.get("memory_key"),"kind",row.get("kind"),"chapter",row.get("chapter_no"),"data",data)));
        }
        ranked.sort(Map.Entry.<Integer,Map<String,Object>>comparingByKey().reversed());
        ranked.stream().limit(50).forEach(entry->relevant.add(entry.getValue()));
        var summaries=store.list("chapters.summaries",id,chapter,chapter%10==0?9:4);
        var previous=store.list("chapters.previous",id,chapter-1);
        String tail=previous.isEmpty()?"":(String)previous.get(0).get("body");
        if(tail.length()>4000) tail=tail.substring(tail.length()-4000);
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("title",novel.get("title"));context.put("premise",novel.get("premise"));context.put("bible",novel.get("bible"));
        context.put("approvedPlan",plan);context.put("chapter",chapter);context.put("memoryVersion",version);
        context.put("facts",relevant);context.put("relationships",relatedGraph);context.put("recentSummaries",summaries);
        context.put("previousEnding",tail);context.put("omittedMatchingFacts",Math.max(0,ranked.size()-50));
        return context;
    }
    public Map<String,Object> recall(Map<String,Object> novel,int chapter,String query,int offset,int limit,String field,int characterOffset,int maxBytes) {
        if(offset<0 || limit<1 || limit>10 || characterOffset<0 || query.length()>400)
            throw new IllegalArgumentException("记忆查询参数无效：offset非负，limit为1至10，关键词最多400字符");
        List<Map<String,Object>> candidates=new ArrayList<>();
        if(query.isBlank()) {
            @SuppressWarnings("unchecked")
            var facts=(List<Map<String,Object>>)contextData(novel,chapter).get("facts");
            candidates.addAll(facts);
        } else {
            for(var row:snapshot(Store.string(novel,"id"),Store.number(novel,"memory_version"))) {
                var data=json.read(Store.string(row,"content"));
                if((field.isBlank() && "RELATION".equals(row.get("kind"))) || data.path("deleted").asBoolean()) continue;
                if(Store.string(row,"memory_key").contains(query) || data.toString().contains(query))
                    candidates.add(Map.of("key",row.get("memory_key"),"kind",row.get("kind"),"chapter",row.get("chapter_no"),"data",data));
            }
            candidates.sort(Comparator.<Map<String,Object>,Boolean>comparing(row->!query.equals(row.get("key")))
                    .thenComparing(Comparator.comparingInt((Map<String,Object> row)->Store.integer(row,"chapter")).reversed())
                    .thenComparing(row->Store.string(row,"key")));
        }
        if(!field.isBlank()) {
            var exact=candidates.stream().filter(row->query.equals(row.get("key"))).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("字段读取需要提供完整记忆key"));
            var value=json.tree(exact.get("data")).at(field);
            if(!value.isTextual()) throw new IllegalArgumentException("记忆字段需要为存在的文本 JSON Pointer");
            return textPage(query,field,value.asText(),characterOffset,maxBytes);
        }
        var facts=new ArrayList<Map<String,Object>>();
        var result=new LinkedHashMap<String,Object>();
        result.put("memoryVersion",novel.get("memory_version"));result.put("facts",facts);
        result.put("totalMatching",candidates.size());result.put("offset",offset);
        int next=Math.min(offset,candidates.size());
        for(;next<candidates.size() && facts.size()<limit;next++) {
            var fact=projection.fact(candidates.get(next));
            facts.add(fact);
            if(projection.bytes(result)>maxBytes-512) { facts.remove(facts.size()-1);break; }
        }
        result.put("nextOffset",next);result.put("hasMore",next<candidates.size());
        result.put("omittedMatchingFacts",Math.max(0,candidates.size()-offset-facts.size()));
        if(facts.isEmpty() && next<candidates.size()) result.put("requiresFieldRead",Map.of(
                "key",candidates.get(next).get("key"),"instruction","使用完整key作为query，field=/detail或/evidence，characterOffset从0开始读取"));
        return result;
    }

    private Map<String,Object> textPage(String key,String field,String text,int offset,int maxBytes) {
        int total=text.codePointCount(0,text.length());
        if(offset>total) throw new IllegalArgumentException("记忆文本读取位置超出范围");
        int low=0,high=Math.min(1000,total-offset);
        while(low<high) {
            int middle=(low+high+1)/2;
            if(projection.bytes(textResult(key,field,text,offset,middle,total))<=maxBytes) low=middle;
            else high=middle-1;
        }
        var result=textResult(key,field,text,offset,low,total);
        if((low==0 && offset<total) || projection.bytes(result)>maxBytes) throw new IllegalStateException("工具剩余预算不足以读取记忆字段");
        return result;
    }

    private Map<String,Object> textResult(String key,String field,String text,int offset,int length,int total) {
        int start=text.offsetByCodePoints(0,offset),end=text.offsetByCodePoints(start,length);
        return Map.of("key",key,"field",field,"text",text.substring(start,end),"characterOffset",offset,
                "nextCharacterOffset",offset+length,"totalCharacters",total,"hasMore",offset+length<total);
    }

    public Object planningContext(Map<String,Object> novel) {
        String id=Store.string(novel,"id");long version=Store.number(novel,"memory_version");
        return Map.of("MEMORY.md",files.readFull(id,version,"MEMORY.md"),"handoff_current.md",files.readFull(id,version,"handoff_current.md"),
                "instruction","使用 list_memory_files、search_memory 和 read_memory 读取阶段、伏笔、支线与人物资料，目录可以分页。");
    }
    public JsonNode validateDelta(JsonNode delta, String body) {
        if(delta.has("files")) return files.validate(delta,body);
        JsonNode normalized=delta.deepCopy();
        var paragraphs=evidence.paragraphs(body);
        for(String section:List.of("facts","relations")) for(var item:normalized.path(section)) {
            if(item.has("evidencePath")) {
                String path=item.path("evidencePath").asText();
                String selected=null;
                for(var paragraph:paragraphs.entrySet()) if(path.equals("/paragraphs/"+paragraph.getKey())) selected=paragraph.getValue();
                if(selected==null) throw new IllegalArgumentException("记忆证据的正文段落不存在："+path);
                var node=(ObjectNode)item;
                node.put("evidence",evidence.source(selected).require(selected,"记忆"));
                node.remove("evidencePath");
            }
        }
        var output=outputs.read(normalized,AgentOutputs.MemoryDelta.class);
        if(output.facts().size()+output.relations().size()>40) throw new IllegalArgumentException("单章记忆变更最多40条");
        Set<String> keys=new HashSet<>();
        var bodyText=evidence.source(body);
        for(int index=0;index<output.facts().size();index++) {
            var fact=output.facts().get(index);
            if(!keys.add(fact.key())) throw new IllegalArgumentException("记忆 key 重复");
            ((ObjectNode)normalized.get("facts").get(index)).put("evidence",
                    bodyText.require(fact.evidence(),"第"+(index+1)+"条事实（"+fact.key()+"）"));
        }
        for(int index=0;index<output.relations().size();index++) {
            var relation=output.relations().get(index);
            String key="relation:"+relation.subject()+":"+relation.predicate()+":"+relation.object();
            if(key.length()>350 || !keys.add(key)) throw new IllegalArgumentException("关系 key 重复或过长");
            ((ObjectNode)normalized.get("relations").get(index)).put("evidence",
                    bodyText.require(relation.evidence(),"第"+(index+1)+"条关系（"+key+"）"));
        }
        return normalized;
    }
    public void commit(String novel,int chapter,long version,JsonNode delta) {
        store.transaction(()-> {
            store.lockNovel(novel);
            var draft=store.list("chapters.find",novel,chapter);
            int revision=draft.isEmpty()?0:Store.integer(draft.get(0),"revision");
            files.commit(novel,chapter,revision,version,delta);return null;
        });
    }
}
