package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AgentMetricsMapper {
    List<Map<String, Object>> summary(@Param("novelId") String novelId);
}
