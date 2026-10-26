package dev.novel.loop;

import io.agentscope.core.tool.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

// 这个 Java 对象会通过 @Tool 注册给 AgentScope。
public final class ReadNotesTool {
    // 程序启动时指定文件位置，模型不能自行改写路径。
    private final Path path;
    // AtomicInteger 让异步工具执行期间的调用统计保持线程安全。
    private final AtomicInteger calls = new AtomicInteger();
    // volatile 确保执行工具的回调线程写入异常后，主流程能及时读取。
    private volatile RuntimeException failure;

    // 构造时先检查文件，尽量在请求模型前发现路径或大小问题。
    public ReadNotesTool(Path path) {
        // 保存本次 Agent 运行指定的文件路径。
        this.path = path;
        // 首次读取会检查文件存在、编码正确且大小在允许范围内。
        readContent();
    }

    // 读取文件并转换成 AgentScope 可以交给模型的文本。
    private String readContent() {
        // try-with-resources 确保文件流在读取后关闭。
        try (var input = Files.newInputStream(path)) {
            // 读取最多4097字节，多出的1字节用于识别是否超限。
            byte[] bytes = input.readNBytes(4097);
            // 文件超过4096字节时立即报错，不把大文件送入模型。
            if (bytes.length > 4096) throw new IllegalArgumentException("说明文件超过4096字节");
            // 严格按 UTF-8 解码，遇到非法字节会报告错误。
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        // 将 Java 的受检读取异常转换成可从 Agent 调用流程中传播的异常。
        } catch (IOException error) {
            // 保留系统异常原因，便于定位文件权限或读取问题。
            throw new UncheckedIOException("无法读取 UTF-8 说明文件", error);
        }
    }

    // @Tool 让 AgentScope 将此方法发布为名为 read_notes 的工具。
    // readOnly 声明它仅读取资料；description 会用于帮助模型决定何时调用。
    @Tool(name = "read_notes", description = "读取本次任务指定的说明文件，查询项目名和验证口令等资料", readOnly = true)
    // 方法没有路径参数，所以模型只能读取启动时指定的文件。
    public String readNotes() {
        // 记录错误后重新抛出，避免 Agent 把失败当成普通工具结果继续回答。
        try {
            // 原子递增并取得当前次数；超过4次就拒绝执行。
            if (calls.incrementAndGet() > 4) throw new IllegalStateException("工具读取超过4次");
            // 只记录工具名和次数，不把说明文件原文写入日志。
            System.out.println("[tool read_notes] 第" + calls.get() + "次读取");
            // 返回原文；AgentScope 会把返回值加入对话，再继续请求模型。
            return readContent();
        } catch (RuntimeException error) {
            // 保存工具错误；框架可能把异常结果传回模型，所以主流程还会检查此字段。
            failure = error;
            // 将原始错误也交给 AgentScope 的工具执行流程。
            throw error;
        }
    }

    // 返回工具执行次数，供最终运行统计使用。
    public int calls() { return calls.get(); }

    // 供 AgentLoop 在模型请求前及最终答复前检查工具状态。
    public void check() {
        // 有工具错误时立刻重新抛出，使整次任务失败。
        if (failure != null) throw failure;
    }
}
