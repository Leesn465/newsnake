package com.mysite.sbb.fastapi.Kafka.DTO;

public record AnalyzeEventDTO(
        String analysisId,
        String url,
        String userId
) {
}