package dev.novel.loop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "LOOP_LIVE", matches = "true")
class AgentLoopLiveTest {
    // AC01、AC02、AC05：两个独立会话，连接真实本机模型。
    @Test
    void answersWithAndWithoutTool() throws Exception {
        var config = LoopConfig.load(Path.of("..", "config", "application-local.yml"));
        var loop = new AgentLoop();
        var direct = loop.run(config, Path.of("notes.md"), "计算1+1，只回答数字，不需要文件资料。");
        assertEquals("2", direct.answer().strip());
        assertEquals(0, direct.toolCalls());
        assertEquals(1, direct.modelCalls());
        var lookup = loop.run(config, Path.of("notes.md"), "调用 read_notes，回答文件中的项目名和验证口令。");
        assertTrue(lookup.answer().contains("Loop Lab"));
        assertTrue(lookup.answer().contains("青竹-7391"));
        assertTrue(lookup.toolCalls() >= 1 && lookup.toolCalls() <= 4);
        assertTrue(lookup.modelCalls() >= 2 && lookup.modelCalls() <= 4);
    }
}
