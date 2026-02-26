package com.mysite.sbb.fastapi.Kafka.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WsAnalyzeEvent(
        String analysisId,
        String type, // PROGRESS | DONE | ERROR
        String message,
        Object payload
) {}