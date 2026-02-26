package com.mysite.sbb.fastapi.optimization;

import com.mysite.sbb.fastapi.CompanyRankDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Sorted Set(ZSet)을 이용한
 * 회사별 실시간 일간 랭킹 관리 서비스
 * <p>
 * 설계 이유:
 * - ZSet은 score 기반 자동 정렬 지원
 * - O(logN)으로 증가 처리 가능
 * - reverseRangeWithScores 로 TopN 조회 효율적
 * <p>
 * Key 전략:
 * rank:company:daily:{yyyy-MM-dd}
 * <p>
 * → 하루 단위로 랭킹 분리
 */
@Service
@RequiredArgsConstructor
public class RedisRankingService {
    private final StringRedisTemplate stringRedisTemplate;

    private String dailyKey(LocalDate date) {
        return "rank:company:daily:" + date;
    }

    /**
     * 오늘 날짜 기준 특정 회사 점수 +1 증가
     * <p>
     * - ZSet score를 1 증가
     * - TTL 3일 설정 (배치 이관 후 자동 만료 대비)
     */
    public void increaseToday(String company) {
        if (company == null || company.isBlank()) return;
        String key = dailyKey(LocalDate.now());
        // ZSet score 증가 (자동 정렬 유지)
        stringRedisTemplate.opsForZSet().incrementScore(key, company, 1.0);
        // 3일 후 만료 (백업 안정성 확보)
        stringRedisTemplate.expire(key, Duration.ofDays(3));
    }

    /**
     * 오늘 기준 Top N 회사 랭킹 조회
     * <p>
     * reverseRangeWithScores:
     * score 높은 순으로 조회
     */
    public List<CompanyRankDto> getTodayTopN(int n) {
        String key = dailyKey(LocalDate.now());
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, n - 1);
        if (tuples == null || tuples.isEmpty()) return List.of();

        List<CompanyRankDto> list = tuples.stream()
                .map(t -> new CompanyRankDto(t.getValue(), t.getScore() == null ? 0L : t.getScore().longValue()))
                .collect(Collectors.toList());

        while (list.size() < n) list.add(new CompanyRankDto("-", 0L));
        return list;
    }

    /**
     * 특정 날짜의 전체 스코어 조회 (배치용)
     * <p>
     * → RankingFlushBatch에서 호출
     * → DB로 영속 저장하기 위한 전체 집계 데이터 반환
     */
    public Map<String, Integer> getAllCounts(LocalDate date) {
        String key = dailyKey(date);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, -1);

        if (tuples == null) return Map.of();

        Map<String, Integer> map = new HashMap<>();
        for (var t : tuples) {
            if (t.getValue() == null) continue;
            int v = (t.getScore() == null) ? 0 : (int) Math.floor(t.getScore());
            map.put(t.getValue(), v);
        }
        return map;
    }

    // 끝난 키는 삭제
    public void delete(LocalDate date) {
        stringRedisTemplate.delete(dailyKey(date));
    }


}
