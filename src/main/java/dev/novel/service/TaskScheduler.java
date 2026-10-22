package dev.novel.service;

import dev.novel.repository.Store;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service("durableTaskScheduler")
public class TaskScheduler {
    private final Store store;
    public TaskScheduler(Store store) { this.store=store; }
    public String schedule(Map<String,Object> novel,String kind,int chapter,int revision,Object payload) {
        String key=novel.get("id")+":"+novel.get("stage_no")+":"+chapter+":"+revision+":"+novel.get("memory_version")+":"+kind;
        return store.enqueue(Store.string(novel,"id"),kind,chapter,revision,Store.number(novel,"memory_version"),key,payload);
    }
}
