package dev.novel.agent;

import dev.novel.integration.model.ModelClient;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CharacterCheckAgent extends ReviewAgent {
    public CharacterCheckAgent(AgentContextService context,ModelClient model,Json json,ReviewValidator validator) { super(context,model,json,validator); }
    public Set<String> kinds() { return Set.of("CHECK_CHARACTER"); }
    public String purpose() { return "人物关系、性格动机、人物知情范围；检查人物是否使用尚未获知的信息"; }
    public List<String> contextFiles() { return AgentInstructions.storyFiles("character_matrix.md","character_knowledge.md"); }
}
