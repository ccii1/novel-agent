package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface MemoryFileMapper {
    Map<String,Object> version(@Param("novel") String novel,@Param("version") long version);
    List<Map<String,Object>> files(@Param("novel") String novel,@Param("version") long version);
    Map<String,Object> file(@Param("novel") String novel,@Param("version") long version,@Param("path") String path);
    Long beforeChapter(@Param("novel") String novel,@Param("chapter") int chapter,@Param("version") long version);
    Long migrationBoundary(@Param("novel") String novel);
    int insertVersion(@Param("novel") String novel,@Param("version") long version,@Param("base") long base,
            @Param("chapter") int chapter,@Param("revision") int revision,@Param("fingerprint") String fingerprint);
    int insertFile(Map<String,Object> file);
}
