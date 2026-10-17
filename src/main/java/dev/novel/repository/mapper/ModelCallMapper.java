package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;

@Mapper
public interface ModelCallMapper {
    int markFramework(@Param("framework") String framework,@Param("taskId") String taskId);
    int complete(@Param("status") String status,@Param("actual") BigDecimal actual,
            @Param("inputTokens") long inputTokens,@Param("outputTokens") long outputTokens,
            @Param("response") String response,@Param("taskId") String taskId);
    int markUncertain(@Param("error") String error,@Param("taskId") String taskId);
}
