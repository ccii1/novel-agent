package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface NovelAgent {
    Set<String> kinds();
    String purpose();
    List<String> contextFiles();
    JsonNode execute(Map<String,Object> task) throws Exception;
}
