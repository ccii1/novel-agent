package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.agent.validation.AgentOutputReader;
import dev.novel.dto.AgentOutputs;
import dev.novel.support.Json;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MemoryPatchService {
    private final MemoryFileService files;
    private final AgentOutputReader outputs;
    private final Json json;
    public MemoryPatchService(MemoryFileService files,AgentOutputReader outputs,Json json) {
        this.files=files;this.outputs=outputs;this.json=json;
    }
    public JsonNode materialize(String novel,long version,JsonNode proposed,String body) {
        if(json.write(proposed).getBytes(StandardCharsets.UTF_8).length>32000)
            throw new IllegalArgumentException("单章记忆修改超过32000字节，请仅提交本章必要变化");
        var patches=outputs.read(proposed,AgentOutputs.MemoryPatches.class);
        var changes=new ArrayList<Map<String,Object>>();
        var paths=new HashSet<String>();
        for(var patch:patches.patches()) {
            String path=patch.path();
            if(!paths.add(path)) throw new IllegalArgumentException("记忆修改路径重复："+path);
            String actual=files.hash(novel,version,path);
            if(!actual.equals(patch.expectedHash())) throw new IllegalStateException("记忆版本不匹配，需要重新读取："+path);
            String content;
            if(actual.isEmpty()) {
                if(patch.content()==null || patch.content().isBlank() || !patch.edits().isEmpty())
                    throw new IllegalArgumentException("新主题需要完整 content 和空 edits："+path);
                content=patch.content();
            } else {
                if(patch.content()!=null || patch.edits().isEmpty())
                    throw new IllegalArgumentException("已有主题仅允许通过 edits 修改："+path);
                String original=files.readFull(novel,version,path);
                var replacements=new TreeMap<Integer,AgentOutputs.MemoryEdit>();
                for(var edit:patch.edits()) {
                    if(!Set.of("current_state.md","handoff_current.md").contains(path)
                            && (edit.oldText().getBytes(StandardCharsets.UTF_8).length>2048 || edit.oldText().equals(original)))
                        throw new IllegalArgumentException("主题修改需要选择局部原文："+path);
                    int start=original.indexOf(edit.oldText());
                    if(start<0 || original.indexOf(edit.oldText(),start+1)>=0)
                        throw new IllegalArgumentException("修改原文需要唯一匹配："+path);
                    var preceding=replacements.floorEntry(start);
                    if(preceding!=null && preceding.getKey()+preceding.getValue().oldText().length()>start)
                        throw new IllegalArgumentException("记忆修改范围重叠："+path);
                    var following=replacements.ceilingEntry(start);
                    if(following!=null && start+edit.oldText().length()>following.getKey())
                        throw new IllegalArgumentException("记忆修改范围重叠："+path);
                    replacements.put(start,edit);
                }
                // 所有位置基于同一份原文计算，逆序应用保证其他内容逐字保留。
                var updated=new StringBuilder(original);
                replacements.descendingMap().forEach((start,edit)->updated.replace(start,start+edit.oldText().length(),edit.newText()));
                content=updated.toString();
            }
            changes.add(Map.of("path",path,"expectedHash",actual,"content",content,
                    "description",patch.description(),"evidencePaths",patch.evidencePaths()));
        }
        var delta=files.validate(json.tree(Map.of("summary",patches.summary(),"files",changes)),body);
        return files.validateBase(novel,version,delta);
    }
}
