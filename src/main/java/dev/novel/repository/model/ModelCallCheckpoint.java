package dev.novel.repository.model;

import java.math.BigDecimal;

public record ModelCallCheckpoint(String status, BigDecimal actual, BigDecimal reserved, String response,
                                  String skillFingerprint) {
}
