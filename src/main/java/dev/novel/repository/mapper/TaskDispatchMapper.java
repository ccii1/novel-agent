package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface TaskDispatchMapper {
    Map<String, Object> nextCandidate();

    int claim(@Param("id") String id);
}
