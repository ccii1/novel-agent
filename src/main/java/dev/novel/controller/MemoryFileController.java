package dev.novel.controller;

import dev.novel.dto.response.MemoryResponses.*;
import dev.novel.service.MemoryFileService;
import dev.novel.support.ResponseMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/novels/{id}/memory-files")
public class MemoryFileController {
    private final MemoryFileService memory;
    private final ResponseMapper responses;
    public MemoryFileController(MemoryFileService memory,ResponseMapper responses) { this.memory=memory;this.responses=responses; }
    @GetMapping
    public MemoryFileList list(@PathVariable String id,@RequestParam(required=false) Long version,
            @RequestParam(defaultValue="") String directory,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="10") int limit) {
        return responses.to(memory.list(id,version==null?memory.currentVersion(id):version,directory,offset,limit),MemoryFileList.class);
    }
    @GetMapping("/content")
    public MemoryFileContent content(@PathVariable String id,@RequestParam(required=false) Long version,@RequestParam String path,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="2000") int length) {
        return responses.to(memory.read(id,version==null?memory.currentVersion(id):version,path,offset,length,16000),MemoryFileContent.class);
    }
    @GetMapping("/search")
    public MemorySearch search(@PathVariable String id,@RequestParam(required=false) Long version,@RequestParam String query,
            @RequestParam(defaultValue="") String directory,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="10") int limit) {
        return responses.to(memory.search(id,version==null?memory.currentVersion(id):version,query,directory,offset,limit,16000),MemorySearch.class);
    }
}
