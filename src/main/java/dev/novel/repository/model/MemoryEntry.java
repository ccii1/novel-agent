package dev.novel.repository.model;

public record MemoryEntry(String id, String novelId, String memoryKey, String kind, int chapterNo, long version,
                          String content) {
}
