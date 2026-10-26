package dev.novel.loop;

import io.agentscope.core.tool.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReadNotesTool {
    private final Path path;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;

    public ReadNotesTool(Path path) {
        this.path = path;
        readContent();
    }

    private String readContent() {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IllegalArgumentException("说明文件超过4096字节");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IOException error) {
            throw new UncheckedIOException("无法读取 UTF-8 说明文件", error);
        }
    }

    @Tool(name = "read_notes", description = "读取本次任务指定的说明文件，查询项目名和验证口令等资料", readOnly = true)
    public String readNotes() {
        try {
            if (calls.incrementAndGet() > 4) throw new IllegalStateException("工具读取超过4次");
            System.out.println("[tool read_notes] 第" + calls.get() + "次读取");
            return readContent();
        } catch (RuntimeException error) {
            // 框架可能将工具异常转成消息，任务最终状态仍须保留该异常。
            failure = error;
            throw error;
        }
    }

    public int calls() { return calls.get(); }

    public void check() {
        if (failure != null) throw failure;
    }
}
