package com.mysite.sbb.fastapi.Kafka.DTO;

import com.mysite.sbb.fastapi.FastApiResponse;

public record AnalyzeAsyncResponseDTO(
        String analysisId,
        boolean cacheHit,
        FastApiResponse result
) {
}