package dev.novel.loop;

import java.nio.file.Path;

public final class LoopMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("参数：本机配置文件 说明文件 用户问题");
        var result = new AgentLoop().run(LoopConfig.load(Path.of(args[0])), Path.of(args[1]), args[2]);
        System.out.println(result.answer());
    }
}
