package com.pharos.compliance.batch.repository.projection;

public record BatchSummaryProjection(long allBatches, long successfulBatches, long attentionBatches, String reportGroupName) {}
