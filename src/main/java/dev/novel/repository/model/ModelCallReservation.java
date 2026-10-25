package dev.novel.repository.model;

import java.math.BigDecimal;

public record ModelCallReservation(String id, String taskId, String novelId, int chapterNo, String billingMonth,
                                   BigDecimal reserved, String model, BigDecimal inputPrice, BigDecimal outputPrice,
                                   String provider, String billingMode,
                                   String skillVersion, String skillFingerprint, String skillFiles, String requestBody,
                                   String agentFramework, String executionId) {
}
