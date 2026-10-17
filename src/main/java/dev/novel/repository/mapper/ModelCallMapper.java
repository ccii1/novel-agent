package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import dev.novel.repository.model.ModelCallCheckpoint;
import dev.novel.repository.model.ModelCallReservation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ModelCallMapper {
    ModelCallCheckpoint checkpoint(@Param("taskId") String taskId);
    Map<String,Object> find(@Param("id") String id);
    List<Map<String,Object>> overview();
    Integer lockBudget();
    long recentCount();
    BigDecimal monthlyUsed(@Param("month") String month);
    BigDecimal novelMonthlyUsed(@Param("novelId") String novelId,@Param("month") String month);
    BigDecimal chapterUsed(@Param("novelId") String novelId,@Param("chapterNo") int chapterNo);
    BigDecimal novelTotal(@Param("novelId") String novelId);
    long pendingTaskCount(@Param("taskId") String taskId);
    long pendingNovelCount(@Param("novelId") String novelId);
    long uncertainTaskCount(@Param("taskId") String taskId);
    int reserve(ModelCallReservation reservation);
    int recoverInterrupted();
    int reconcile(@Param("actual") BigDecimal actual,@Param("id") String id);
    int complete(@Param("status") String status,@Param("actual") BigDecimal actual,
            @Param("inputTokens") long inputTokens,@Param("outputTokens") long outputTokens,
            @Param("response") String response,@Param("callId") String callId);
    int markUncertain(@Param("error") String error,@Param("callId") String callId);
}
