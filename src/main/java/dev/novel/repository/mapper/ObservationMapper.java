package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface ObservationMapper {
    int start(@Param("id") String id,@Param("task") Map<String,Object> task,@Param("source") String source);
    int finish(@Param("id") String id,@Param("status") String status,@Param("error") String error,
            @Param("result") String result,@Param("metrics") Map<String,Object> metrics);
    int interruptRunning();
    List<Map<String,Object>> summary(Map<String,Object> filter);
    List<Map<String,Object>> tasks(Map<String,Object> filter);
    long count(Map<String,Object> filter);
    Map<String,Object> task(@Param("id") String id);
    Map<String,Object> call(@Param("id") String id);
    List<Map<String,Object>> executions(@Param("id") String id);
    List<Map<String,Object>> events(@Param("id") String id);
    List<Map<String,Object>> feedback(@Param("id") String id);
    int addFeedback(@Param("id") String id,@Param("rating") int rating,@Param("note") String note);
}
