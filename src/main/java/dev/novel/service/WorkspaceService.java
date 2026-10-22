package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class WorkspaceService {
    private final Store store;
    private final MemoryService memory;
    private final ModelCallMapper calls;
    public record Manuscript(String title,String body) {}
    public WorkspaceService(Store store,MemoryService memory,ModelCallMapper calls) {this.store=store;this.memory=memory;this.calls=calls;}
    public List<Map<String,Object>> novels() { return store.list("novels.all"); }
    public List<Map<String,Object>> novelSummaries() { return store.list("novels.summaries"); }
    public Map<String,Object> snapshot(String id) { return store.one("novels.snapshot",id); }
    public Map<String,Object> call(String id) {
        var result=calls.find(id);
        if(result==null) throw new NoSuchElementException("调用记录不存在");
        return result;
    }
    public Map<String,Object> chapter(String id,int number) { return store.one("chapters.find",id,number); }
    public List<Map<String,Object>> researchReports() { return store.list("research.all"); }
    public List<Map<String,Object>> researchTasks() { return store.list("tasks.research"); }
    public void published(String id,int number,boolean published) {
        store.transaction(() -> {
            store.lockNovel(id);
            int changed=store.update("chapters.publish",published,id,number);
            if(changed!=1) throw new IllegalStateException("只能标记已通过检查的章节");
            store.event(id,null,"PUBLICATION_MARKED","第"+number+"章"+(published?"已标记发布":"已取消发布标记"));
            return null;
        });
    }
    public Map<String,Object> graph(String id,List<String> characters,int depth) {
        return memory.graph(id,Store.number(store.novel(id),"memory_version"),characters.stream().filter(s->!s.isBlank()).toList(),depth);
    }
    public List<Map<String,Object>> memories(String id) { return memory.snapshot(id,Store.number(store.novel(id),"memory_version")); }
    public Manuscript manuscript(String id) {
        String title=Store.string(store.novel(id),"title");StringBuilder text=new StringBuilder(title).append("\n\n");
        store.list("chapters.accepted",id)
                .forEach(chapter->text.append("第").append(chapter.get("number")).append("章 ").append(chapter.get("title")).append("\n\n").append(chapter.get("body")).append("\n\n"));
        return new Manuscript(title,text.toString());
    }
}
