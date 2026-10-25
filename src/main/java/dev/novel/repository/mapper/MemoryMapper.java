package dev.novel.repository.mapper;

import dev.novel.repository.model.MemoryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryMapper {
    List<Map<String, Object>> snapshot(@Param("novelId") String novelId, @Param("version") long version);

    List<Map<String, Object>> baseline(@Param("novelId") String novelId, @Param("beforeChapter") int beforeChapter, @Param("version") long version);

    int insert(MemoryEntry entry);
}
