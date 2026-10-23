package dev.novel.support;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Text;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryMarkdown {
    private final Parser parser=Parser.builder().extensions(List.of(YamlFrontMatterExtension.create())).build();
    private final YAMLMapper yaml=new YAMLMapper();
    private final Json json;
    public MemoryMarkdown(Json json) { this.json=json; }
    public record Document(String id,String type,String name,List<String> aliases,List<String> headings,List<String> links,String legacy) {}
    public Document parse(String content) {
        var node=parser.parse(content);
        var metadata=new YamlFrontMatterVisitor();node.accept(metadata);
        var data=metadata.getData();
        var headings=new ArrayList<String>();
        var legacy=new ArrayList<String>();
        var links=new ArrayList<String>();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Link link) { links.add(link.getDestination());visitChildren(link); }
            @Override public void visit(Heading heading) {
                var text=new StringBuilder();
                heading.accept(new AbstractVisitor() { @Override public void visit(Text value) { text.append(value.getLiteral()); } });
                headings.add(text.toString());
            }
            @Override public void visit(FencedCodeBlock block) {
                if("memory-record".equals(block.getInfo())) legacy.add(block.getLiteral());
            }
        });
        if(legacy.size()>1) throw new IllegalArgumentException("记忆文件只能包含一个历史记录附录");
        String id=first(data,"id"),type=first(data,"type"),name=first(data,"name");
        if(id.isBlank() || type.isBlank() || name.isBlank() || headings.isEmpty())
            throw new IllegalArgumentException("记忆 Markdown 需要 id、type、name metadata 和标题");
        if(id.length()>350 || name.length()>300 || id.contains("\n") || name.contains("\n") || !Set.of("index","state","handoff","world","character","hook","arc","chapter","stage","relation","issue").contains(type))
            throw new IllegalArgumentException("记忆文件标识、名称或类型无效");
        return new Document(id,type,name,List.copyOf(data.getOrDefault("aliases",List.of())),List.copyOf(headings),List.copyOf(links),legacy.isEmpty()?"":legacy.get(0));
    }
    private String first(Map<String,List<String>> data,String key) {
        var values=data.get(key);return values==null || values.isEmpty()?"":values.get(0);
    }
    public String create(String id,String type,String name,List<String> aliases,String body) {
        try {
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("id",id);metadata.put("type",type);metadata.put("name",name);metadata.put("aliases",aliases);
            return yaml.writeValueAsString(metadata)+"---\n\n# "+name+"\n\n"+body.strip()+"\n";
        } catch(java.io.IOException failure) { throw new IllegalStateException("记忆 Markdown metadata 写入失败",failure); }
    }
    public String legacy(String key,String kind,String name,com.fasterxml.jackson.databind.JsonNode data) {
        String type=switch(kind) {case "WORLD"->"world";case "CHARACTER"->"character";case "FORESHADOW"->"hook";case "RELATION"->"relation";default->"chapter";};
        String body="## 记录\n\n"+data.path("detail").asText(name)+"\n\n## 历史记录附录\n\n```memory-record\n"+json.write(data)+"\n```\n";
        return create(key,type,name,List.of(),body);
    }
}
