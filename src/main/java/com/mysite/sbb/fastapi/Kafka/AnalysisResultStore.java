package com.mysite.sbb.fastapi.Kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.fastapi.FastApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 분석 결과 임시 저장소 (Redis 기반)
 * <p>
 * 목적:
 * - Kafka 비동기 분석 완료 후 결과를 Redis에 저장
 * - 클라이언트가 /analyze-result/{analysisId} 로 조회 가능
 * - TTL을 둬서 일정 시간 후 자동 삭제
 * <p>
 * 설계 의도:
 * 1. DB 부하 방지 (임시 조회는 Redis 사용)
 * 2. 비동기 요청-응답 패턴 구현
 * 3. TTL 기반 자동 만료
 */
@Service
@RequiredArgsConstructor
public class AnalysisResultStore {

    /**
     * 결과 캐시 유지 시간 (10분)
     */
    private static final long TTL_MINUTES = 10;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Redis Key 생성
     * 형식: analysis:result:{analysisId}
     */
    private String key(String analysisId) {
        return "analysis:result:" + analysisId;
    }

    /**
     * 분석 결과 저장
     * <p>
     * - FastApiResponse → JSON 직렬화
     * - TTL 설정하여 자동 만료
     */
    public void putResult(String analysisId, FastApiResponse resp) {
        try {
            String json = objectMapper.writeValueAsString(resp);

            redisTemplate.opsForValue()
                    .set(key(analysisId), json, TTL_MINUTES, TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException("Redis 저장 실패", e);
        }
    }

    /**
     * 분석 결과 조회
     * <p>
     * - Redis에서 JSON 조회
     * - 역직렬화 후 반환
     * - 존재하지 않으면 null (아직 분석 중이거나 만료됨)
     */
    public FastApiResponse getResult(String analysisId) {
        try {
            String json = redisTemplate.opsForValue().get(key(analysisId));

            if (json == null) return null;

            return objectMapper.readValue(json, FastApiResponse.class);

        } catch (Exception e) {
            throw new RuntimeException("Redis 조회 실패", e);
        }
    }
}