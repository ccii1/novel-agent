package dev.novel;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.util.FileSystemUtils;

public final class TestResourceCleanup extends AbstractTestExecutionListener {
    private final Map<Path,Set<Path>> baseline=new LinkedHashMap<>();
    private FileChannel channel;
    private FileLock lock;
    @Override public void beforeTestClass(TestContext context) throws Exception {
        Path root=Path.of(".work/tests").toAbsolutePath().normalize();
        Files.createDirectories(root);
        channel=FileChannel.open(root.resolve("suite.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=channel.tryLock();
        if(lock==null) { channel.close();throw new IllegalStateException("另一个进程正在使用项目测试资源"); }
        boolean ready=false;
        try {
            var environment=context.getApplicationContext().getEnvironment();
            if(!environment.getProperty("novel.tests.keep-files",Boolean.class,false)) {
                for(String property:List.of("novel.memory-directory","novel.agent-state-directory")) {
                    Path directory=Path.of(environment.getRequiredProperty(property)).toAbsolutePath().normalize();
                    if(!directory.startsWith(root) || directory.equals(root)) throw new IllegalStateException("测试文件目录必须位于 .work/tests 的子目录");
                    baseline.put(directory,children(directory));
                }
            }
            ready=true;
        } finally { if(!ready) { lock.release();channel.close(); } }
    }
    @Override public void afterTestClass(TestContext context) throws Exception {
        try {
            for(var entry:baseline.entrySet()) for(Path child:children(entry.getKey())) {
                if(entry.getValue().contains(child)) continue;
                if(!child.startsWith(entry.getKey()) || child.equals(entry.getKey()) || Files.isSymbolicLink(child))
                    throw new IllegalStateException("测试清理路径不符合授权范围："+child);
                FileSystemUtils.deleteRecursively(child);
            }
        } finally {
            baseline.clear();
            if(lock!=null && lock.isValid()) lock.release();
            if(channel!=null && channel.isOpen()) channel.close();
        }
    }
    private Set<Path> children(Path directory) throws Exception {
        if(!Files.exists(directory)) return Set.of();
        if(Files.isSymbolicLink(directory)) throw new IllegalStateException("测试目录不能是符号链接");
        try(var paths=Files.list(directory)) { return new HashSet<>(paths.toList()); }
    }
}
