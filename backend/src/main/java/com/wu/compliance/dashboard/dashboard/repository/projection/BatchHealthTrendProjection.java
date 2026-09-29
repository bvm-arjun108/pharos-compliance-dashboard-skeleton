package com.wu.compliance.dashboard.dashboard.repository.projection;

import java.time.LocalDate;

public record BatchHealthTrendProjection(LocalDate periodStart, long batchesRan, long successfulBatches, long batchesNeedingAttention) {}
