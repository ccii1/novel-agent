package dev.novel.service;

import dev.novel.repository.Store;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class WorkspaceService {
    private final Store store;
    private final MemoryService memory;
    public record Manuscript(String title,String body) {}
    public WorkspaceService(Store store,MemoryService memory) {this.store=store;this.memory=memory;}
    public List<Map<String,Object>> novels() { return store.list("SELECT * FROM novels ORDER BY created_at DESC"); }
    public Map<String,Object> call(String id) { return store.one("SELECT * FROM model_calls WHERE id=?",id); }
    public Map<String,Object> chapter(String id,int number) { return store.one("SELECT * FROM chapters WHERE novel_id=? AND number=?",id,number); }
    public List<Map<String,Object>> researchReports() { return store.list("SELECT * FROM research ORDER BY created_at DESC LIMIT 30"); }
    public List<Map<String,Object>> researchTasks() { return store.list("SELECT * FROM tasks WHERE kind IN ('RESEARCH','RESEARCH_CHAT') ORDER BY created_at DESC LIMIT 60"); }
    public void published(String id,int number,boolean published) {
        int changed=store.update("UPDATE chapters SET published=? WHERE novel_id=? AND number=? AND status='ACCEPTED'",published,id,number);
        if(changed!=1) throw new IllegalStateException("只能标记已通过检查的章节");
    }
    public Object graph(String id,List<String> characters,int depth) {
        return memory.graph(id,Store.number(store.novel(id),"memory_version"),characters.stream().filter(s->!s.isBlank()).toList(),depth);
    }
    public Object memories(String id) { return memory.snapshot(id,Store.number(store.novel(id),"memory_version")); }
    public Manuscript manuscript(String id) {
        String title=Store.string(store.novel(id),"title");StringBuilder text=new StringBuilder(title).append("\n\n");
        store.list("SELECT number,title,body FROM chapters WHERE novel_id=? AND status='ACCEPTED' ORDER BY number",id)
                .forEach(chapter->text.append("第").append(chapter.get("number")).append("章 ").append(chapter.get("title")).append("\n\n").append(chapter.get("body")).append("\n\n"));
        return new Manuscript(title,text.toString());
    }
}
