package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.ObservationMapper;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ObservationService {
    private final ObservationMapper observations;
    private final ModelCallMapper calls;
    private final Store store;
    private final Json json;
    private final DatabaseStorage storage;
    public ObservationService(ObservationMapper observations,ModelCallMapper calls,Store store,Json json,DatabaseStorage storage) {
        this.observations=observations;this.calls=calls;this.store=store;this.json=json;this.storage=storage;
    }
    public void start(Map<String,Object> task) {
        String id=UUID.randomUUID().toString();
        // 认领与执行记录在调用方的同一事务中保存。
        if(observations.start(id,task,task.get("result")!=null || calls.checkpoint(Store.string(task,"id"))!=null?"CHECKPOINT":"GENERATED")!=1)
            throw new IllegalStateException("Agent 执行记录未成功创建");
        task.put("execution_id",id);
    }
    public void finish(Map<String,Object> task,String status,JsonNode result,String error) {
        Object id=task.get("execution_id");
        if(id==null) return;
        Map<String,Object> metrics=new HashMap<>();
        if(result!=null) {
            if(result.has("approved")) metrics.put("approved",result.path("approved").asBoolean());
            if(result.has("issues")) metrics.put("issueCount",result.path("issues").size());
            if(result.has("majorChange")) metrics.put("majorChange",result.path("majorChange").asBoolean());
            if(result.has("body")) metrics.put("bodyCharacters",Math.toIntExact(result.path("body").asText().codePoints().filter(p->!Character.isWhitespace(p)).count()));
            if(result.has("facts")) metrics.put("factCount",result.path("facts").size());
            if(result.has("relations")) metrics.put("relationCount",result.path("relations").size());
        }
        if(observations.finish(id.toString(),status,error,result==null?null:json.write(result),metrics)!=1)
            throw new IllegalStateException("Agent 执行记录未处于运行状态");
    }
    public void recover() { observations.interruptRunning(); }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> dashboard(String novelId,Integer chapter,String kind,String status,int page) {
        if(page<0 || page>100000 || chapter!=null && chapter<0) throw new IllegalArgumentException("分页或章节编号无效");
        if(novelId!=null) store.novel(novelId);
        Map<String,Object> filter=new HashMap<>();
        filter.put("novelId",novelId);filter.put("chapter",chapter);filter.put("kind",kind);filter.put("status",status);
        filter.put("limit",30);filter.put("offset",page*30);
        return Map.of("summary",observations.summary(filter),"tasks",observations.tasks(filter),
                "total",observations.count(filter),"page",page,"pageSize",30,
                "storage",storage.description(),"historicalTiming","新增观测前的任务可能没有耗时记录；中断执行的结束时间未知");
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(String id) {
        var task=observations.task(id);
        if(task==null) throw new NoSuchElementException("任务记录不存在");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("task",task);result.put("executions",observations.executions(id));
        result.put("events",observations.events(id));result.put("feedback",observations.feedback(id));
        result.put("call",observations.call(id));
        result.put("turns",store.list("modelTurns.list",id));
        return result;
    }
    @Transactional
    public void feedback(String id,int rating,String note) {
        var task=observations.task(id);
        if(task==null) throw new NoSuchElementException("任务记录不存在");
        if(task.get("result")==null) throw new IllegalArgumentException("任务尚无通过结构检查的输出");
        if(rating<1 || rating>5 || note==null || note.isBlank() || note.length()>4000)
            throw new IllegalArgumentException("请选择1至5分并填写最多4000字符的评价");
        observations.addFeedback(id,rating,note.trim());
    }
}
