package dev.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import dev.novel.agent.validation.AgentOutputReader;
import dev.novel.dto.AgentOutputs;
import dev.novel.repository.Store;
import dev.novel.repository.mapper.MemoryFileMapper;
import dev.novel.repository.mapper.MemoryMapper;
import dev.novel.support.EvidenceMatcher;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class MemoryFileService {
    private final Path root;
    private final int fileBytes,entryBytes;
    private final Store store;
    private final MemoryFileMapper mapper;
    private final MemoryMapper legacy;
    private final MemoryMarkdown markdown;
    private final Json json;
    private final AgentOutputReader outputs;
    private final EvidenceMatcher evidence;
    private static final String GUIDE="按主题读取记忆。人物与伏笔目录支持关键词搜索和分页；只读取当前任务需要的资料。";
    public MemoryFileService(@Value("${novel.memory-directory:./data/books}") String root,
            @Value("${novel.memory-file-bytes:16000}") int fileBytes,@Value("${novel.memory-entry-bytes:6000}") int entryBytes,
            Store store,MemoryFileMapper mapper,MemoryMapper legacy,MemoryMarkdown markdown,Json json,
            AgentOutputReader outputs,EvidenceMatcher evidence) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.fileBytes=fileBytes;this.entryBytes=entryBytes;
        if(fileBytes<1024 || entryBytes<1024 || entryBytes>fileBytes) throw new IllegalArgumentException("记忆文件与入口预算配置无效");
        this.store=store;this.mapper=mapper;this.legacy=legacy;this.markdown=markdown;this.json=json;this.outputs=outputs;this.evidence=evidence;
    }
    public static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    public long currentVersion(String novel) { return Store.number(store.novel(novel),"memory_version"); }
    private void access(String novel,long version) {
        var current=store.novel(novel);
        if(version<0) throw new IllegalArgumentException("记忆版本不能为负数");
        if(!novel.matches("[a-zA-Z0-9-]{1,36}")) throw new IllegalArgumentException("作品目录标识无效");
        String user=dev.novel.security.UserScope.current();
        if(user!=null && !user.equals(current.get("user_id"))) throw new IllegalStateException("无权读取其他用户的记忆文件");
    }
    public String path(String path) {
        if(path==null || path.length()>300 || !path.matches("(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_-]+\\.md"))
            throw new IllegalArgumentException("记忆路径需要为授权目录中的 Markdown 相对路径");
        return path;
    }
    private Path location(String novel,String physical) {
        Path book=root.resolve(novel).resolve("memory").normalize(),target=book.resolve(physical).normalize();
        if(!target.startsWith(book) || target.equals(book)) throw new IllegalArgumentException("记忆文件路径超出作品目录");
        for(Path cursor=target;cursor!=null && cursor.startsWith(root);cursor=cursor.getParent())
            if(Files.isSymbolicLink(cursor)) throw new IllegalStateException("记忆目录不能包含符号链接");
        return target;
    }
    private String content(String novel,Map<String,Object> row) {
        try {
            Path target=location(novel,Store.string(row,"physical_path"));
            if(Files.size(target)!=Store.integer(row,"bytes")) throw new IllegalStateException("记忆文件长度与已发布版本不一致："+row.get("path"));
            String text=Files.readString(target,StandardCharsets.UTF_8);
            if(!SkillService.digest(text).equals(row.get("fingerprint"))) throw new IllegalStateException("记忆文件指纹与已发布版本不一致："+row.get("path"));
            return text;
        } catch(IOException failure) { throw new IllegalStateException("已发布的记忆文件无法读取："+row.get("path"),failure); }
    }
    private Map<String,Object> write(String novel,long version,String session,String path,String text,String description,int chapter,int revision) {
        var document=markdown.parse(text);
        String physical="versions/v"+String.format("%08d",version)+"/"+session+"/"+path(path);
        Path target=location(novel,physical);
        try {
            Files.createDirectories(target.getParent());
            try(var channel=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while(buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch(IOException failure) { throw new IllegalStateException("不可变记忆文件准备失败："+path,failure); }
        var row=new LinkedHashMap<String,Object>();
        row.put("novel_id",novel);row.put("version",version);row.put("path",path);row.put("physical_path",physical);
        row.put("fingerprint",SkillService.digest(text));row.put("bytes",bytes(text));row.put("title",document.name());
        row.put("description",description);row.put("kind",document.legacy().isBlank()?document.type():"legacy-"+document.type());row.put("entity_key",document.id());
        row.put("chapter_no",chapter);row.put("source_revision",revision);return row;
    }
    public void ensure(String novel,long version) {
        access(novel,version);
        if(mapper.version(novel,version)!=null) return;
        store.transaction(()-> {
            var book=store.lockNovel(novel);
            if(mapper.version(novel,version)!=null) return null;
            if(version>Store.number(book,"memory_version")) throw new IllegalStateException("请求的记忆版本尚未发布："+version);
            // 未建立文件版本的作品通过一次迁移保存原记录，已有文件版本缺失属于错误。
            if(!mapper.files(novel,version).isEmpty()) throw new IllegalStateException("记忆文件版本清单不完整");
            Long boundary=mapper.migrationBoundary(novel);
            if(boundary!=null && version>boundary)
                throw new IllegalStateException("请求的记忆版本不存在："+version);
            var documents=new TreeMap<String,String>();
            var originals=legacy.snapshot(novel,version);
            for(var row:originals) {
                var data=json.read(Store.string(row,"content"));
                if(data.path("deleted").asBoolean(false)) continue;
                String key=Store.string(row,"memory_key"),kind=Store.string(row,"kind");
                documents.put(legacyPath(key,kind),markdown.legacy(key,kind,data.path("name").asText(key),data));
            }
            var summaries=store.list("chapters.versionSummaries",novel,version);
            int chapter=Math.max(originals.stream().mapToInt(row->Store.integer(row,"chapter_no")).max().orElse(0),
                    summaries.stream().mapToInt(row->Store.integer(row,"number")).max().orElse(0));
            String session=UUID.randomUUID().toString();
            var rows=new TreeMap<String,Map<String,Object>>();
            for(var original:originals) {
                String path=legacyPath(Store.string(original,"memory_key"),Store.string(original,"kind"));
                if(documents.containsKey(path)) rows.put(path,write(novel,version,session,path,documents.get(path),"迁移的历史记忆",Store.integer(original,"chapter_no"),0));
            }
            addGenerated(novel,version,session,rows,"MEMORY.md",entry(),"主题记忆入口",chapter,0);
            addGenerated(novel,version,session,rows,"current_state.md",markdown.create("current-state","state","当前状态",List.of(),
                    documents.isEmpty()?"当前尚无正式人物与事件记忆，按核心设定和批准的大纲创作。":
                    "请根据本章人物读取对应主题文件，核对状态、物品、关系与知情范围。\n\n历史记忆已迁移到主题目录；尚未进行正文来源复核的记录保留原有证据。"),"当前状态导航",chapter,0);
            addGenerated(novel,version,session,rows,"handoff_current.md",markdown.create("handoff","handoff","章节交接",List.of(),
                    "下一章："+(chapter+1)+"。读取批准的大纲、相关人物与未解决伏笔。"),"章节交接",chapter,0);
            for(var summary:summaries) {
                int number=Store.integer(summary,"number");
                addGenerated(novel,version,session,rows,chapterPath(number),markdown.create("chapter-"+number,"chapter","第"+number+"章记录",List.of(),Store.string(summary,"summary")),"已确认章节摘要",number,0);
            }
            addIndexes(novel,version,session,rows,chapter,0);
            register(novel,version,version,chapter,0,"migration:"+version,rows);return null;
        });
    }
    public String legacyPath(String key,String kind) {
        String directory=switch(kind) {case "WORLD"->"world";case "CHARACTER"->"characters";case "FORESHADOW"->"hooks";case "RELATION"->"relations";default->"events";};
        return directory+"/"+SkillService.digest(key).substring(0,24)+".md";
    }
    private String entry() {
        return markdown.create("memory-index","index","记忆目录",List.of(),GUIDE+"\n\n"+
                "- 当前状态：current_state.md\n- 章节交接：handoff_current.md\n- 人物与别名：characters/INDEX.md\n"+
                "- 世界规则：world/INDEX.md\n- 未解决伏笔：hooks/INDEX.md\n- 支线：arcs/INDEX.md\n"+
                "- 历史事件：events/INDEX.md\n- 人物关系：relations/INDEX.md\n- 阶段记录：stages/INDEX.md\n"+
                "- 章节记录：chapters/INDEX.md\n- 待确认问题：issues/INDEX.md\n\n搜索标识、姓名、别名、物品和地点，按范围读取必要原文；没有命中时保留不确定性。\n");
    }
    private void addGenerated(String novel,long version,String session,Map<String,Map<String,Object>> rows,
            String path,String text,String description,int chapter,int revision) {
        if(rows.containsKey(path) && SkillService.digest(text).equals(rows.get(path).get("fingerprint"))) return;
        rows.put(path,write(novel,version,session,path,text,description,chapter,revision));
    }
    private void addIndexes(String novel,long version,String session,Map<String,Map<String,Object>> rows,int chapter,int revision) {
        for(String directory:List.of("characters","world","hooks","arcs","events","relations","stages","chapters","issues")) {
            var body=new StringBuilder("按标识、名称、别名或关键词搜索；目录可以分页读取。\n\n");
            rows.forEach((path,row)-> {
                if(path.startsWith(directory+"/") && !path.endsWith("/INDEX.md"))
                    body.append("- ").append(row.get("entity_key")).append(" · ").append(row.get("title")).append(" · ").append(row.get("description")).append("：").append(path).append('\n');
            });
            String path=directory+"/INDEX.md";
            addGenerated(novel,version,session,rows,path,markdown.create(directory+"-index","index",directory+" 目录",List.of(),body.toString()),"主题文件目录",chapter,revision);
        }
    }
    private String chapterPath(int chapter) { return "chapters/ch_"+String.format("%04d",chapter)+".md"; }
    private void register(String novel,long version,long base,int chapter,int revision,String fingerprint,Map<String,Map<String,Object>> rows) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("记忆文件发布需要业务事务");
        mapper.insertVersion(novel,version,base,chapter,revision,SkillService.digest(fingerprint));
        rows.values().forEach(row->{var saved=new LinkedHashMap<>(row);saved.put("version",version);mapper.insertFile(saved);});
    }
    public List<Map<String,Object>> manifest(String novel,long version) { ensure(novel,version);return mapper.files(novel,version); }
    public void verifyPublished(String novel,long version) {
        access(novel,version);
        if(mapper.version(novel,version)==null) return;
        var rows=mapper.files(novel,version);
        var paths=new HashSet<String>();rows.forEach(row->paths.add(Store.string(row,"path")));
        if(!paths.containsAll(Set.of("MEMORY.md","current_state.md","handoff_current.md"))) throw new IllegalStateException("已发布记忆清单缺少必要入口文件");
        for(var row:rows) content(novel,row);
    }
    public String readFull(String novel,long version,String path) {
        ensure(novel,version);var row=mapper.file(novel,version,path(path));
        if(row==null) throw new NoSuchElementException("记忆文件不存在："+path);
        return content(novel,row);
    }
    public String hash(String novel,long version,String path) {
        ensure(novel,version);var row=mapper.file(novel,version,path(path));return row==null?"":Store.string(row,"fingerprint");
    }
    public Map<String,Object> list(String novel,long version,String directory,int offset,int limit) {
        if(offset<0 || limit<1 || limit>30) throw new IllegalArgumentException("文件列表分页范围无效");
        if(!directory.isEmpty() && !directory.matches("[a-zA-Z0-9_-]+/?")) throw new IllegalArgumentException("记忆目录无效");
        String prefix=directory.isEmpty()?"":directory.replaceAll("/$","")+"/";
        var matches=manifest(novel,version).stream().filter(row->Store.string(row,"path").startsWith(prefix)).toList();
        var selected=matches.stream().skip(offset).limit(limit).map(this::descriptor).toList();
        int next=Math.min(matches.size(),offset+selected.size());
        return Map.of("memoryVersion",version,"files",selected,"total",matches.size(),"nextOffset",next,"hasMore",next<matches.size());
    }
    private Map<String,Object> descriptor(Map<String,Object> row) {
        return Map.of("path",row.get("path"),"title",row.get("title"),"description",row.get("description"),"hash",row.get("fingerprint"),"bytes",row.get("bytes"),"legacy",Store.string(row,"kind").startsWith("legacy-"));
    }
    public Map<String,Object> read(String novel,long version,String path,int offset,int length,int budget) {
        String text=readFull(novel,version,path);
        int total=text.codePointCount(0,text.length());
        if(offset<0 || offset>total || length<1 || length>4000) throw new IllegalArgumentException("记忆读取范围无效");
        int end=Math.min(total,offset+length),start=text.offsetByCodePoints(0,offset);
        Map<String,Object> result;
        do {
            result=Map.of("path",path,"memoryVersion",version,"hash",hash(novel,version,path),"content",text.substring(start,text.offsetByCodePoints(0,end)),
                    "offset",offset,"nextOffset",end,"totalCharacters",total,"hasMore",end<total);
            if(bytes(json.write(result))<=budget) return result;
            end=offset+(end-offset)/2;
        } while(end>offset);
        throw new IllegalStateException("工具剩余预算不足以读取文件");
    }
    public Map<String,Object> search(String novel,long version,String query,String directory,int offset,int limit,int budget) {
        if(query==null || query.isBlank() || query.length()>200 || offset<0 || limit<1 || limit>20)
            throw new IllegalArgumentException("记忆搜索需要关键词及有效分页范围");
        if(!directory.isEmpty() && !directory.matches("[a-zA-Z0-9_-]+/?")) throw new IllegalArgumentException("记忆搜索目录无效");
        var matches=new ArrayList<Map<String,Object>>();int count=0;
        String needle=query.toLowerCase(Locale.ROOT);
        var ordered=new ArrayList<>(manifest(novel,version));
        ordered.sort(Comparator.<Map<String,Object>,Boolean>comparing(row->"index".equals(row.get("kind")))
                .thenComparing(row->Store.string(row,"kind").startsWith("legacy-"))
                .thenComparing(Comparator.comparingInt((Map<String,Object> row)->Store.integer(row,"chapter_no")).reversed())
                .thenComparing(row->Store.string(row,"path")));
        for(var row:ordered) {
            String path=Store.string(row,"path");
            if(!directory.isEmpty() && !path.startsWith(directory.replaceAll("/$","")+"/")) continue;
            String text=content(novel,row);
            boolean metadata=(path+" "+row.get("title")+" "+row.get("entity_key")).toLowerCase(Locale.ROOT).contains(needle);
            var lines=text.lines().toList();boolean found=false;
            for(int line=0;line<lines.size();line++) {
                if(!lines.get(line).toLowerCase(Locale.ROOT).contains(needle) && !(metadata && line==0)) continue;
                found=true;
                if(count++>=offset && matches.size()<limit) {
                    String snippet=lines.get(line);if(snippet.length()>200) snippet=snippet.substring(0,200);
                    matches.add(Map.of("path",path,"line",line+1,"text",snippet,"hash",row.get("fingerprint")));
                }
                // 每个文件返回首个命中，避免同一事实在目录和正文中占满一页。
                if(found) break;
            }
        }
        while(!matches.isEmpty() && bytes(json.write(matches))>budget-500) matches.remove(matches.size()-1);
        if(matches.isEmpty() && count>offset) throw new IllegalStateException("工具剩余预算不足以返回搜索命中");
        return Map.of("memoryVersion",version,"matches",matches,"total",count,"nextOffset",Math.min(count,offset+matches.size()),"hasMore",offset+matches.size()<count);
    }
    public JsonNode validate(JsonNode delta,String body) {
        var output=outputs.read(delta,AgentOutputs.MemoryFiles.class);
        Set<String> paths=new HashSet<>(),ids=new HashSet<>();
        var paragraphs=evidence.paragraphs(body);
        for(var change:output.files()) {
            String path=path(change.path());
            if(!paths.add(path) || path.equals("MEMORY.md") || path.endsWith("/INDEX.md") || path.startsWith("chapters/"))
                throw new IllegalArgumentException("记忆修改路径重复或属于系统维护的目录："+path);
            if(!path.equals("current_state.md") && !path.equals("handoff_current.md") && !path.matches("(?:characters|world|hooks|arcs|stages|events|relations|issues)/[^/]+\\.md"))
                throw new IllegalArgumentException("记忆文件需要保存到规定的主题目录");
            if(bytes(change.content())>fileBytes || ((path.equals("current_state.md") || path.equals("handoff_current.md")) && bytes(change.content())>entryBytes))
                throw new IllegalArgumentException("记忆文件超过预算，请按主题组织内容："+path);
            var document=markdown.parse(change.content());
            String expectedType=path.equals("current_state.md")?"state":path.equals("handoff_current.md")?"handoff":switch(path.substring(0,path.indexOf('/'))) {
                case "characters"->"character";case "world"->"world";case "hooks"->"hook";case "arcs"->"arc";case "stages"->"stage";
                case "events"->"chapter";case "relations"->"relation";default->"issue";
            };
            if(!expectedType.equals(document.type())) throw new IllegalArgumentException("记忆文件类型与目录不一致："+path);
            if(!document.legacy().isBlank()) throw new IllegalArgumentException("新记忆修改不能写入历史结构附录");
            if(!ids.add(document.id())) throw new IllegalArgumentException("候选记忆文件标识重复");
            for(String reference:change.evidencePaths()) {
                if(!reference.startsWith("/paragraphs/") || !paragraphs.containsKey(reference.substring("/paragraphs/".length())))
                    throw new IllegalArgumentException("记忆修改的正文证据不存在："+reference);
                String source=paragraphs.get(reference.substring("/paragraphs/".length()));
                evidence.source(source).require(source,"记忆文件来源");
            }
        }
        if(!paths.containsAll(Set.of("current_state.md","handoff_current.md")))
            throw new IllegalArgumentException("每章必须更新当前状态与交接文件");
        var normalized=(ObjectNode)delta.deepCopy();normalized.putArray("facts");normalized.putArray("relations");return normalized;
    }
    public JsonNode validateBase(String novel,long base,JsonNode delta) {
        ensure(novel,base);
        Set<String> changed=new HashSet<>();delta.path("files").forEach(item->changed.add(item.path("path").asText()));
        var available=new HashSet<>(changed);
        mapper.files(novel,base).forEach(row->available.add(Store.string(row,"path")));
        available.add(chapterPath(Store.integer(store.novel(novel),"chapter_no")));
        var ids=new HashMap<String,String>();
        var relationFiles=new HashMap<String,String>();
        for(var row:mapper.files(novel,base)) if(!changed.contains(row.get("path")) && Set.of("relation","character").contains(row.get("kind")))
            validateRelationKeys(markdown.parse(content(novel,row)),Store.string(row,"path"),relationFiles);
        for(var row:mapper.files(novel,base)) if(!changed.contains(row.get("path"))) ids.put(Store.string(row,"entity_key"),Store.string(row,"path"));
        for(var item:delta.path("files")) {
            String path=item.path("path").asText(),expected=item.path("expectedHash").asText();
            if(!hash(novel,base,path).equals(expected)) throw new IllegalStateException("记忆文件已变化，需要重新读取："+path);
            var document=markdown.parse(item.path("content").asText());
            validateRelationKeys(document,path,relationFiles);
            var old=mapper.file(novel,base,path);
            if(old!=null && !document.id().equals(old.get("entity_key"))) throw new IllegalArgumentException("已有记忆文件需要保留稳定标识："+path);
            for(String destination:document.links()) {
                var uri=java.net.URI.create(destination);
                if(uri.getScheme()!=null) {
                    if(!Set.of("http","https").contains(uri.getScheme())) throw new IllegalArgumentException("记忆链接协议无效");
                    continue;
                }
                String target=uri.getPath();if(target==null || target.isBlank()) continue;
                if(!available.contains(target)) {
                    Path parent=Path.of(path).getParent();
                    target=(parent==null?Path.of(target):parent.resolve(target)).normalize().toString().replace('\\','/');
                }
                if(!available.contains(path(target))) throw new IllegalArgumentException("记忆文件链接不存在："+destination);
            }
            String previous=ids.putIfAbsent(document.id(),path);
            if(previous!=null) throw new IllegalArgumentException("同一记忆标识已经保存在文件："+previous);
        }
        return delta;
    }
    private void validateRelationKeys(MemoryMarkdown.Document document,String path,Map<String,String> locations) {
        for(var relation:document.relations()) {
            String key=json.write(List.of(relation.path("subject").asText(),relation.path("predicate").asText(),relation.path("object").asText()));
            String previous=locations.putIfAbsent(key,path);
            if(previous!=null) throw new IllegalArgumentException("关系重复记录，需要在同一文件维护："+previous+"；"+path);
        }
    }
    public record Prepared(String novel,long version,long base,int chapter,int revision,String delta,
            Map<String,Map<String,Object>> rows) {}
    public Prepared prepare(String novel,int chapter,int revision,long version,JsonNode delta) {
        long base=version-1;ensure(novel,base);
        if(delta.has("files")) validateBase(novel,base,delta);
        String session=UUID.randomUUID().toString();var rows=new TreeMap<String,Map<String,Object>>();
        mapper.files(novel,base).forEach(row->rows.put(Store.string(row,"path"),row));
        if(delta.has("files")) {
            for(var item:delta.path("files")) addGenerated(novel,version,session,rows,item.path("path").asText(),item.path("content").asText(),item.path("description").asText(),chapter,revision);
        } else {
            for(String section:List.of("facts","relations")) for(var item:delta.path(section)) {
                String key=section.equals("facts")?item.path("key").asText():"relation:"+item.path("subject").asText()+":"+item.path("predicate").asText()+":"+item.path("object").asText();
                String kind=section.equals("facts")?item.path("kind").asText():"RELATION",path=legacyPath(key,kind);
                if(item.path("deleted").asBoolean(false)) { rows.remove(path);continue; }
                addGenerated(novel,version,session,rows,path,markdown.legacy(key,kind,item.path("name").asText(key),item),"保留原始证据的历史格式记录",chapter,revision);
            }
        }
        StringBuilder record=new StringBuilder("## 本章摘要\n\n").append(delta.path("summary").asText("本章记忆已更新。")).append("\n\n## 记忆变化\n\n");
        for(var item:delta.path("files")) {
            String path=item.path("path").asText();
            record.append("### ").append(path).append("\n\n").append(item.path("description").asText()).append("\n\n来源：第")
                    .append(chapter).append("章 revision ").append(revision).append(' ').append(item.path("evidencePaths")).append("\n\n");
            String before=hash(novel,base,path).isEmpty()?"":readFull(novel,base,path);
            record.append("```diff\n").append(diff(path,before,item.path("content").asText())).append("\n```\n\n");
        }
        addGenerated(novel,version,session,rows,chapterPath(chapter),markdown.create("chapter-"+chapter,"chapter","第"+chapter+"章记录",List.of(),record.toString()),"本章摘要与记忆变化",chapter,revision);
        addIndexes(novel,version,session,rows,chapter,revision);
        return new Prepared(novel,version,base,chapter,revision,json.write(delta),Collections.unmodifiableMap(rows));
    }
    public void publish(Prepared prepared) {
        register(prepared.novel(),prepared.version(),prepared.base(),prepared.chapter(),prepared.revision(),prepared.delta(),prepared.rows());
    }
    public void commit(String novel,int chapter,int revision,long version,JsonNode delta) {
        publish(prepare(novel,chapter,revision,version,delta));
    }
    public Map<String,Object> reviewInput(String novel,long base,JsonNode delta,int index,String body) {
        if(index<0 || index>=delta.path("files").size()) throw new IllegalArgumentException("记忆审核分组不存在");
        var change=delta.path("files").get(index);String path=change.path("path").asText();
        String previous=hash(novel,base,path).isEmpty()?"":readFull(novel,base,path);
        String next=change.path("content").asText();
        String diff=diff(path,previous,next);
        Map<String,String> sources=new LinkedHashMap<>();var paragraphs=evidence.paragraphs(body);
        for(var reference:change.path("evidencePaths")) sources.put(reference.asText(),paragraphs.get(reference.asText().substring("/paragraphs/".length())));
        return Map.of("fileIndex",index,"path",path,"description",change.path("description"),"diff",diff,"evidence",sources,
                "instruction","根据来源检查修改，必要时读取人物、伏笔与关联主题文件；问题引用 /files/"+index+"/content 或正文路径。");
    }
    private String diff(String path,String before,String after) {
        var original=before.lines().toList();
        return String.join("\n",UnifiedDiffUtils.generateUnifiedDiff(path,path,original,DiffUtils.diff(original,after.lines().toList()),3));
    }
    public List<List<Integer>> reviewGroups(String novel,long base,JsonNode delta,String body) {
        var pending=new LinkedHashSet<Integer>();
        for(int index=0;index<delta.path("files").size();index++) pending.add(index);
        var groups=new ArrayList<List<Integer>>();
        while(!pending.isEmpty()) {
            var connected=new ArrayList<Integer>();var queue=new ArrayDeque<Integer>();
            int first=pending.iterator().next();pending.remove(first);queue.add(first);
            while(!queue.isEmpty()) {
                int index=queue.remove();connected.add(index);
                Set<String> sources=new HashSet<>();delta.path("files").get(index).path("evidencePaths").forEach(value->sources.add(value.asText()));
                var related=new ArrayList<Integer>();
                for(int other:pending) for(var source:delta.path("files").get(other).path("evidencePaths"))
                    if(sources.contains(source.asText())) { related.add(other);break; }
                related.forEach(other->{pending.remove(other);queue.add(other);});
            }
            var batch=new ArrayList<Integer>();
            for(int index:connected) {
                var trial=new ArrayList<>(batch);trial.add(index);
                if(!batch.isEmpty() && bytes(json.write(reviewGroupInput(novel,base,delta,trial,body)))>24000) {
                    groups.add(List.copyOf(batch));batch.clear();
                }
                batch.add(index);
            }
            if(!batch.isEmpty()) groups.add(List.copyOf(batch));
        }
        return List.copyOf(groups);
    }
    public Object reviewGroupInput(String novel,long base,JsonNode delta,List<Integer> indices,String body) {
        var changes=new ArrayList<Map<String,Object>>();var sources=new LinkedHashMap<String,Object>();
        for(int index:indices) {
            var single=new LinkedHashMap<>(reviewInput(novel,base,delta,index,body));
            var evidence=json.tree(single.remove("evidence"));
            evidence.fields().forEachRemaining(entry->sources.put(entry.getKey(),entry.getValue()));
            single.put("evidenceRefs",delta.path("files").get(index).path("evidencePaths"));changes.add(single);
        }
        return Map.of("files",changes,"evidenceCatalog",sources,
                "instruction","共同核对关联文件的状态变化，正文证据按引用共享。需要其他原文或关联资料时使用工具读取。");
    }
    public Object coverage(JsonNode delta) {
        var changes=new ArrayList<Map<String,Object>>();int index=0;
        for(var item:delta.path("files")) changes.add(Map.of("index",index++,"path",item.path("path"),"description",item.path("description"),"evidencePaths",item.path("evidencePaths"),"headings",markdown.parse(item.path("content").asText()).headings()));
        return Map.of("summary",delta.path("summary"),"changes",changes,"instruction","检查正文的重要变化是否都有文件记录，核对跨文件关联；必要时读取候选文件或原有主题。不能仅根据修改说明确认内容正确。");
    }
    public Map<String,String> export(String novel,long version) {
        var result=new TreeMap<String,String>();for(var row:manifest(novel,version)) result.put(Store.string(row,"path"),content(novel,row));return result;
    }
    public List<Map<String,Object>> snapshot(String novel,long version) {
        var result=new ArrayList<Map<String,Object>>();
        var nativeRelations=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:manifest(novel,version)) {
            if(Set.of("index","state","handoff").contains(row.get("kind")) || Store.string(row,"path").startsWith("chapters/")) continue;
            String text=content(novel,row);var doc=markdown.parse(text);
            String kind=switch(doc.type()) {case "world"->"WORLD";case "character"->"CHARACTER";case "hook"->"FORESHADOW";case "relation"->doc.legacy().isBlank()?"EVENT":"RELATION";default->"EVENT";};
            String data=doc.legacy().isBlank()?json.write(Map.of("name",doc.name(),"detail",text,"path",row.get("path"))):json.write(json.read(doc.legacy()));
            result.add(Map.of("id",row.get("fingerprint"),"novel_id",novel,"memory_key",doc.id(),"kind",kind,"chapter_no",row.get("chapter_no"),"version",version,"content",data));
            for(var relation:doc.relations()) {
                String key="relation:"+json.write(List.of(relation.path("subject").asText(),relation.path("predicate").asText(),relation.path("object").asText()));
                String memoryKey="relation:"+relation.path("subject").asText()+":"+relation.path("predicate").asText()+":"+relation.path("object").asText();
                var projected=Map.<String,Object>of("id",row.get("fingerprint"),"novel_id",novel,"memory_key",memoryKey,"kind","RELATION",
                        "chapter_no",row.get("chapter_no"),"version",version,"content",json.write(relation));
                var previous=nativeRelations.putIfAbsent(key,projected);
                if(previous!=null && !previous.get("content").equals(projected.get("content")))
                    throw new IllegalStateException("多个 Markdown 文件包含冲突关系："+key);
            }
        }
        result.removeIf(row->{
            if(!"RELATION".equals(row.get("kind"))) return false;
            var relation=json.read(Store.string(row,"content"));
            return nativeRelations.containsKey("relation:"+json.write(List.of(relation.path("subject").asText(),relation.path("predicate").asText(),relation.path("object").asText())));
        });
        result.addAll(nativeRelations.values());
        return result;
    }
    public void restore(String novel,long version,int chapter) {
        long base=version-1;ensure(novel,base);
        Long target=mapper.beforeChapter(novel,chapter,base);
        var rows=new TreeMap<String,Map<String,Object>>();
        if(target==null) {
            // 早于首次文件迁移的历史修改从原始历史记录恢复，保留修改章节之前的有效内容。
            String session=UUID.randomUUID().toString();
            for(var row:legacy.baseline(novel,chapter,base)) {
                var data=json.read(Store.string(row,"content"));if(data.path("deleted").asBoolean()) continue;
                String key=Store.string(row,"memory_key"),kind=Store.string(row,"kind");
                addGenerated(novel,version,session,rows,legacyPath(key,kind),markdown.legacy(key,kind,data.path("name").asText(key),data),
                        "历史修改前的有效记忆",Store.integer(row,"chapter_no"),0);
            }
            addGenerated(novel,version,session,rows,"MEMORY.md",entry(),"主题记忆入口",chapter-1,0);
            addGenerated(novel,version,session,rows,"current_state.md",markdown.create("current-state","state","当前状态",List.of(),
                    "已恢复到第"+chapter+"章之前。读取相关主题并重新规划。"),"历史修改交接",chapter-1,0);
            addGenerated(novel,version,session,rows,"handoff_current.md",markdown.create("handoff","handoff","章节交接",List.of(),
                    "从第"+chapter+"章重新规划，原章节及后续章节尚未重新确认。"),"历史修改交接",chapter-1,0);
            addIndexes(novel,version,session,rows,chapter-1,0);
        } else {
            ensure(novel,target);
            for(var row:mapper.files(novel,target)) rows.put(Store.string(row,"path"),row);
        }
        register(novel,version,base,chapter-1,0,"restore:"+target,rows);
    }
    public void stage(Map<String,Object> task,JsonNode delta) {
        String novel=Store.string(task,"novel_id");long base=Store.number(task,"memory_version");
        validateBase(novel,base,delta);
        String taskId=Store.string(task,"id");
        if(!taskId.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("候选目录的任务标识无效");
        for(var item:delta.path("files")) {
            Path target=location(novel,"staging/"+taskId+"/"+path(item.path("path").asText()));
            try { Files.createDirectories(target.getParent());Files.writeString(target,item.path("content").asText(),StandardCharsets.UTF_8); }
            catch(IOException failure) { throw new IllegalStateException("候选记忆文件保存失败",failure); }
        }
    }
}
