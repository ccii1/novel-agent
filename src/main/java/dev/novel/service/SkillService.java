package dev.novel.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class SkillService {
    private static final String ROOT = "skills/fanqie-novel-skill/";
    private static final String ADAPTATION = """
            应用执行规则：按当前任务输出指定 JSON，直接读取提供的 Truth Files 状态，无需向用户重复索取交接文件。
            Truth Files 是数据库当前版本的只读资料，正式状态由业务流程统一提交。
            持续连载不预设总字数；阶段大纲、重要剧情变更和历史修改按人工审批流程执行。
            每月总费用额度300元；只允许使用当前任务授权的状态、工具和输出范围。
            字数目标2000至2500字作为参考，先完整生成并保存草稿，再根据内容质量整理；字数差异单独记录为建议，不作为保存或审核失败的独立原因。
            禁止为达到字数凑写或删去关键事实。对话占比按含引号的非空行数计算，默认上限40%。
            战斗检查依据实际题材和批准的章纲触发，高潮也可以来自悬疑揭示、智斗或情感冲突。
            文风检查结合语境与原文证据，修改保持语义完整。
            前三章检查开篇吸引力，每五章回顾叙事重复，每十章按完整维度回顾。
            上游关于平台政策、签约、流量和收益的表述属于待核查资料；不得视为已验证事实或承诺。
            """;
    private final Properties origin = new Properties();
    private final Map<String,String> sources=new LinkedHashMap<>();
    private final Map<String,Snapshot> snapshots=new java.util.concurrent.ConcurrentHashMap<>();
    public record Snapshot(String instruction, String fingerprint, List<String> files, String version, String commit) {}
    public SkillService() {
        try (var input = new ClassPathResource(ROOT + "origin.properties").getInputStream()) {
            origin.load(input);
        } catch (IOException e) { throw new IllegalStateException("技能来源记录无法读取", e); }
        if (!digest(read("SKILL.md")).equals(origin.getProperty("skill-sha256")))
            throw new IllegalStateException("技能主文件与固定版本指纹不一致");
        for (String reference : List.of("ai-detox-guide.md", "audit-dimensions.md", "battle-scene-guide.md",
                "commercial-review.md", "dialogue-monitor.md", "fanqie-platform-rules.md", "pattern-detection.md"))
            verify("references/" + reference,read("references/" + reference));
        sources.put("SKILL.md",read("SKILL.md"));
        for(String reference:List.of("ai-detox-guide.md","audit-dimensions.md","battle-scene-guide.md",
                "commercial-review.md","dialogue-monitor.md","fanqie-platform-rules.md","pattern-detection.md"))
            sources.put("references/"+reference,read("references/"+reference));
    }
    public Snapshot load(String role, int chapter) {
        String family=switch(role) {
            case "REWRITE","REFLECT"->"WRITE";
            case "MEMORY_REPAIR"->"MEMORY";
            case "CHECK_CHARACTER","CHECK_PLOT","CHECK_PACING","CHECK_STYLE"->"CHECK_CHAPTER";
            case "RESEARCH_CHAT"->"RESEARCH";
            default->role;
        };
        return snapshots.computeIfAbsent(family,this::compile);
    }
    private Snapshot compile(String role) {
        var files = new LinkedHashSet<String>();
        files.add("SKILL.md");
        switch (role) {
            case "RESEARCH", "RESEARCH_CHAT" -> references(files, "commercial-review.md", "fanqie-platform-rules.md");
            case "PLAN", "PLAN_CHANGE", "COORDINATE" -> references(files, "commercial-review.md", "pattern-detection.md");
            case "WRITE", "REWRITE" -> references(files, "ai-detox-guide.md", "battle-scene-guide.md", "dialogue-monitor.md");
            case "CHECK_PACING" -> references(files, "battle-scene-guide.md", "dialogue-monitor.md", "pattern-detection.md");
            case "CHECK_STYLE" -> references(files, "ai-detox-guide.md", "commercial-review.md");
            case "CHECK_CHAPTER" -> references(files,"audit-dimensions.md","battle-scene-guide.md","dialogue-monitor.md","pattern-detection.md","ai-detox-guide.md","commercial-review.md");
            case "REFLECT" -> references(files, "audit-dimensions.md", "ai-detox-guide.md");
            case "CHECK_CHARACTER", "CHECK_PLOT", "MEMORY", "MEMORY_REPAIR", "CHECK_MEMORY" -> references(files, "audit-dimensions.md");
            default -> throw new IllegalArgumentException("未知技能角色：" + role);
        }
        String rules=read("runtime-rules.md");
        StringBuilder provenance=new StringBuilder(rules);
        for (String file : files) {
            String content = sources.get(file);
            verify(file,content);
            provenance.append(file).append(content);
        }
        files.add("runtime-rules.md");
        String instruction=ADAPTATION+"\n"+rules+"\n角色范围="+role+"；技能版本="+origin.getProperty("version")+
                "；来源指纹="+digest(provenance.toString())+"。本轮只执行角色授权的检查和修改。";
        return new Snapshot(instruction, digest(provenance+instruction), List.copyOf(files),
                origin.getProperty("version"), origin.getProperty("commit"));
    }
    public Map<String,Object> status() {
        return Map.of("name", "fanqie-novel-skill", "version", origin.getProperty("version"),
                "commit", origin.getProperty("commit"), "required", true, "mainFingerprint", origin.getProperty("skill-sha256"));
    }
    private void references(Set<String> files, String... names) {
        for (String name : names) files.add("references/" + name);
    }
    private void verify(String file,String content) {
        String expected=file.equals("SKILL.md")?origin.getProperty("skill-sha256"):origin.getProperty("sha256."+file.substring("references/".length()));
        if(!digest(content).equals(expected)) throw new IllegalStateException("技能文件与固定版本指纹不一致："+file);
    }
    private String read(String path) {
        try (var input = new ClassPathResource(ROOT + path).getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) throw new IllegalStateException("技能文件为空：" + path);
            return content;
        } catch (IOException e) { throw new IllegalStateException("必需的技能文件无法读取：" + path, e); }
    }
    public static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("JDK 无法提供 SHA-256", e); }
    }
}
