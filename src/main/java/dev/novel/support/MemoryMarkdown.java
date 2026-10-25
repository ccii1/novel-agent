package dev.novel.support;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Text;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.commonmark.parser.IncludeSourceSpans;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MemoryMarkdown {
    private final Parser parser = Parser.builder().extensions(List.of(YamlFrontMatterExtension.create())).includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private final YAMLMapper yaml = new YAMLMapper();
    private final Json json;

    public MemoryMarkdown(Json json) {
        this.json = json;
    }

    public record Document(String id, String type, String name, List<String> aliases, List<String> headings,
                           List<String> links, String legacy,
                           List<com.fasterxml.jackson.databind.JsonNode> relations) {
    }

    public Document parse(String content) {
        var node = parser.parse(content);
        if (!(node.getFirstChild() instanceof YamlFrontMatterBlock header))
            throw new IllegalArgumentException("记忆 Markdown 缺少 YAML metadata");
        var spans = header.getSourceSpans();
        com.fasterxml.jackson.databind.JsonNode data;
        try {
            data = yaml.readTree(content.substring(spans.get(0).getInputIndex() + spans.get(0).getLength(), spans.get(spans.size() - 1).getInputIndex()));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("记忆 YAML metadata 格式无效", failure);
        }
        if (data == null || !data.isObject()) throw new IllegalArgumentException("记忆 YAML metadata 需要对象格式");
        for (String field : List.of("id", "type", "name"))
            if (!data.path(field).isTextual())
                throw new IllegalArgumentException("记忆 YAML 字段需要文本：" + field);
        var headings = new ArrayList<String>();
        var legacy = new ArrayList<String>();
        var links = new ArrayList<String>();
        var relations = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                links.add(link.getDestination());
                visitChildren(link);
            }

            @Override
            public void visit(Heading heading) {
                var text = new StringBuilder();
                heading.accept(new AbstractVisitor() {
                    @Override
                    public void visit(Text value) {
                        text.append(value.getLiteral());
                    }
                });
                headings.add(text.toString());
            }

            @Override
            public void visit(FencedCodeBlock block) {
                if ("memory-record".equals(block.getInfo())) legacy.add(block.getLiteral());
                if ("story-relations".equals(block.getInfo())) {
                    var records = json.read(block.getLiteral());
                    if (!records.isArray()) throw new IllegalArgumentException("story-relations 必须为 JSON 数组");
                    for (var relation : records) {
                        for (String field : List.of("subject", "predicate", "object", "detail", "evidence"))
                            if (!relation.path(field).isTextual() || relation.path(field).asText().isBlank() || relation.path(field).asText().length() > 4000)
                                throw new IllegalArgumentException("story-relations 缺少有效字段：" + field);
                        if (!relation.path("deleted").isBoolean())
                            throw new IllegalArgumentException("story-relations 需要 deleted 布尔字段");
                        if (relation.path("subject").asText().length() > 300 || relation.path("object").asText().length() > 300 || relation.path("predicate").asText().length() > 150)
                            throw new IllegalArgumentException("关系名称最多300字符，类型最多150字符");
                        relations.add(relation);
                    }
                }
            }
        });
        if (legacy.size() > 1) throw new IllegalArgumentException("记忆文件只能包含一个历史记录附录");
        String id = data.path("id").asText(), type = data.path("type").asText(), name = data.path("name").asText();
        if (id.isBlank() || type.isBlank() || name.isBlank() || headings.isEmpty())
            throw new IllegalArgumentException("记忆 Markdown 需要 id、type、name metadata 和标题");
        if (id.length() > 350 || name.length() > 300 || id.contains("\n") || name.contains("\n") || !Set.of("index", "state", "handoff", "world", "character", "hook", "arc", "chapter", "stage", "relation", "issue").contains(type))
            throw new IllegalArgumentException("记忆文件标识、名称或类型无效");
        if (!relations.isEmpty() && !Set.of("relation", "character").contains(type))
            throw new IllegalArgumentException("story-relations 只能写入人物或关系文件");
        var aliases = new ArrayList<String>();
        if (data.has("aliases")) {
            if (!data.path("aliases").isArray()) throw new IllegalArgumentException("aliases 需要 YAML 列表");
            for (var alias : data.path("aliases")) {
                if (!alias.isTextual() || alias.asText().isBlank())
                    throw new IllegalArgumentException("别名需要有效文本");
                aliases.add(alias.asText());
            }
        }
        return new Document(id, type, name, List.copyOf(aliases), List.copyOf(headings), List.copyOf(links), legacy.isEmpty() ? "" : legacy.get(0), List.copyOf(relations));
    }

    public String create(String id, String type, String name, List<String> aliases, String body) {
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("id", id);
            metadata.put("type", type);
            metadata.put("name", name);
            metadata.put("aliases", aliases);
            return yaml.writeValueAsString(metadata) + "---\n\n# " + name + "\n\n" + body.strip() + "\n";
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("记忆 Markdown metadata 写入失败", failure);
        }
    }

    public String legacy(String key, String kind, String name, com.fasterxml.jackson.databind.JsonNode data) {
        String type = switch (kind) {
            case "WORLD" -> "world";
            case "CHARACTER" -> "character";
            case "FORESHADOW" -> "hook";
            case "RELATION" -> "relation";
            default -> "chapter";
        };
        String body = "## 记录\n\n" + data.path("detail").asText(name) + "\n\n## 历史记录附录\n\n```memory-record\n" + json.write(data) + "\n```\n";
        return create(key, type, name, List.of(), body);
    }
}
