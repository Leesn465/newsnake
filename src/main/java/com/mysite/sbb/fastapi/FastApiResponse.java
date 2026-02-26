package com.mysite.sbb.fastapi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FastApiResponse(
        String message,
        String title,
        String time,
        String content,
        String thumbnail_url,
        String url,
        String summary,
        String company,
        List<Map<String, Object>> keyword,
        String sentiment,
        String prediction,
        float prob,
        LocalDateTime createdAt
) {
}