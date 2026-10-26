package dev.novel.loop;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopTest {
    // AC03：使用真实文件验证输入边界，不调用模型。
    @Test
    void rejectsInvalidInput() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AgentLoop.validateTask(" "));
        assertThrows(IllegalArgumentException.class, () -> AgentLoop.validateTask("a".repeat(2001)));
        Path directory = Path.of("target", "file-checks");
        Files.createDirectories(directory);
        assertThrows(java.io.UncheckedIOException.class, () -> new ReadNotesTool(directory.resolve("missing.md")));
        Path file = directory.resolve("notes.md");
        Files.writeString(file, "a".repeat(4096));
        var tool = new ReadNotesTool(file);
        assertEquals(4096, tool.readNotes().length());
        Files.writeString(file, "a".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> new ReadNotesTool(file));
        assertThrows(IllegalArgumentException.class, tool::readNotes);
        assertThrows(IllegalArgumentException.class, tool::check);
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(java.io.UncheckedIOException.class, () -> new ReadNotesTool(file));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentLoop().run(null, file, " "));
        assertThrows(java.io.UncheckedIOException.class,
                () -> new AgentLoop().run(null, directory.resolve("missing.md"), "读取说明"));
    }

    // AC04：工具次数上限及异常保留。
    @Test
    void limitsToolReads() throws Exception {
        Path directory = Path.of("target", "file-checks");
        Files.createDirectories(directory);
        Path file = directory.resolve("bounded.md");
        Files.writeString(file, "本地说明");
        var tool = new ReadNotesTool(file);
        for (int count = 0; count < 4; count++) assertEquals("本地说明", tool.readNotes());
        assertThrows(IllegalStateException.class, tool::readNotes);
        assertThrows(IllegalStateException.class, tool::check);
    }
}
