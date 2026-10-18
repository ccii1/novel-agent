package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
import dev.novel.support.EvidenceMatcher;
import dev.novel.agent.AgentOutputReader;
import dev.novel.dto.AgentOutputs;
import dev.novel.repository.mapper.MemoryMapper;
import dev.novel.repository.model.MemoryEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MemoryService {
    private final Store store;
    private final Json json;
    private final MemoryMapper memories;
    private final AgentOutputReader outputs;
    private final MemoryProjectionCache cache;
    private final EvidenceMatcher evidence;
    public MemoryService(Store store,Json json,MemoryMapper memories,AgentOutputReader outputs,MemoryProjectionCache cache,EvidenceMatcher evidence) {
        this.store=store;this.json=json;this.memories=memories;this.outputs=outputs;this.cache=cache;this.evidence=evidence;
    }
    public List<Map<String,Object>> snapshot(String novel, long version) {
        return cache.snapshot(novel,version,()->memories.snapshot(novel,version));
    }
    public Map<String,Object> graph(String novel, long version, List<String> characters, int depth) {
        if(depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        var selected=characters.stream().distinct().sorted().toList();
        return cache.graph(novel,version,json.write(Map.of("characters",selected,"depth",depth)),
                ()->graph(snapshot(novel,version),version,selected,depth));
    }
    private Map<String,Object> graph(List<Map<String,Object>> facts,long version,List<String> characters,int depth) {
        if (depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        TinkerGraph graph = TinkerGraph.open();
        Map<String,Vertex> vertices = new LinkedHashMap<>();
        for (var row : facts) {
            if (!"RELATION".equals(row.get("kind"))) continue;
            var data = json.read((String)row.get("content"));
            if (data.path("deleted").asBoolean(false)) continue;
            String from=json.text(data,"subject"), to=json.text(data,"object"), predicate=json.text(data,"predicate");
            Vertex source=vertices.computeIfAbsent(from,n->graph.addVertex(T.id,n,"name",n));
            Vertex target=vertices.computeIfAbsent(to,n->graph.addVertex(T.id,n,"name",n));
            source.addEdge(predicate,target,"chapter",row.get("chapter_no"),"evidence",data.path("evidence").asText(),"detail",data.path("detail").asText());
        }
        Set<Object> selected = new LinkedHashSet<>();
        if (characters.isEmpty()) selected.addAll(vertices.keySet());
        else {
            var traversal=graph.traversal();
            for (String character : characters) {
                if (!vertices.containsKey(character)) continue;
                selected.add(character);
                selected.addAll(traversal.V(character).repeat(__.both().simplePath()).emit().times(depth).dedup().id().toList());
            }
        }
        List<Map<String,Object>> nodes=new ArrayList<>(), edges=new ArrayList<>();
        for (Object id : selected) nodes.add(Map.of("id",id,"name",id));
        graph.edges().forEachRemaining(edge -> {
            if (selected.contains(edge.outVertex().id()) && selected.contains(edge.inVertex().id())) edges.add(Map.of(
                    "subject",edge.outVertex().id(),"predicate",edge.label(),"object",edge.inVertex().id(),
                    "chapter",edge.value("chapter"),"detail",edge.value("detail"),"evidence",edge.value("evidence")));
        });
        return Map.of("version",version,"nodes",nodes,"edges",edges);
    }
    public String context(Map<String,Object> novel, int chapter) {
        return json.write(contextData(novel,chapter));
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
        var relatedGraph=cache.graph(id,version,json.write(Map.of("characters",characters.stream().distinct().sorted().toList(),"depth",2)),
                ()->graph(snapshot,version,characters,2));
        String terms=json.write(relatedGraph.get("nodes"));
        Set<String> requestedKeys=new HashSet<>();
        plan.path("chapters").forEach(item->{if(item.path("number").asInt()==chapter) item.path("foreshadowIds").forEach(key->requestedKeys.add(key.asText()));});
        List<Map.Entry<Integer,Map<String,Object>>> ranked=new ArrayList<>();
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
    public Object planningContext(Map<String,Object> novel) {
        var snapshot=snapshot((String)novel.get("id"),Store.number(novel,"memory_version"));
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        for(String kind:List.of("WORLD","CHARACTER","FORESHADOW","EVENT")) grouped.put(kind,new ArrayList<>());
        for(var row:snapshot) {
            String kind=Store.string(row,"kind");
            var selected=grouped.get(kind);
            if(selected==null || selected.size()>=(kind.equals("FORESHADOW")?30:15)) continue;
            var data=json.read(Store.string(row,"content"));
            if(data.path("deleted").asBoolean(false) || (kind.equals("FORESHADOW") && !data.path("active").asBoolean(false))) continue;
            selected.add(Map.of("key",row.get("memory_key"),"chapter",row.get("chapter_no"),"data",data));
        }
        Map<String,Object> result=new LinkedHashMap<>(grouped);
        result.put("retainedMemoryCount",snapshot.size());return result;
    }
    public JsonNode validateDelta(JsonNode delta, String body) {
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
    private String relationKey(JsonNode relation) {
        return "relation:"+json.text(relation,"subject")+":"+json.text(relation,"predicate")+":"+json.text(relation,"object");
    }
    public void commit(String novel,int chapter,long version,JsonNode delta) {
        for(var fact:delta.path("facts")) save(novel,chapter,version,json.text(fact,"key"),json.text(fact,"kind"),fact);
        for(var relation:delta.path("relations")) save(novel,chapter,version,relationKey(relation),"RELATION",relation);
    }
    private void save(String novel,int chapter,long version,String key,String kind,JsonNode content) {
        memories.insert(new MemoryEntry(UUID.randomUUID().toString(),novel,key,kind,chapter,version,json.write(content)));
    }
}
