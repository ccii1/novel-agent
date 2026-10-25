package dev.novel.service;

import dev.novel.repository.Store;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MemoryFileHealthService implements ApplicationRunner {
    private final Store store;
    private final MemoryFileService files;

    public MemoryFileHealthService(Store store, MemoryFileService files) {
        this.store = store;
        this.files = files;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        for (var novel : store.list("novels.all")) {
            String id = Store.string(novel, "id");
            try {
                files.verifyPublished(id, Store.number(novel, "memory_version"));
            } catch (IllegalStateException failure) {
                // 文件校验失败的作品暂停，其他作品仍然可以独立运行。
                store.update("novels.fileFailure", failure.getMessage(), id);
                store.event(id, null, "ERROR", failure.getMessage());
            }
        }
    }
}
