package com.mysite.sbb.fastapi.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

/**
 * Redis에 저장된 일간 회사별 조회/분석 카운트를
 * RDB(company_rank_daily 테이블)로 이관하는 배치 작업.
 * <p>
 * 설계 의도:
 * 1. 실시간 트래픽 집계는 Redis에서 처리 (빠른 쓰기)
 * 2. 하루가 끝나면 RDB로 영속 저장 (통계/랭킹용)
 * 3. 이후 주간/월간 랭킹은 RDB 집계 기반으로 계산
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RankingFlushBatch {
    private final RedisRankingService redisRankingService;
    private final CompanyRankDailyRepository companyRankDailyRepository;


    /**
     * 매일 00:05 (KST) 실행
     * <p>
     * - 전날 Redis 집계 데이터를 조회
     * - DB에 upsert 저장
     * - Redis 데이터 삭제
     * - 주간/월간 캐시 무효화
     * <p>
     * 캐시 무효화 이유:
     * 일간 데이터가 갱신되었으므로
     * 주간/월간 랭킹 재계산 필요
     */

    @Caching(evict = {
            @CacheEvict(cacheNames = "rankWeekly", allEntries = true),
            @CacheEvict(cacheNames = "rankMonthly", allEntries = true)
    })
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void flushYesterday() {
        LocalDate target = LocalDate.now().minusDays(1);
        // Redis에서 회사별 카운트 전체 조회
        Map<String, Integer> counts = redisRankingService.getAllCounts(target);
        // 집계 데이터가 없으면 종료
        if (counts.isEmpty()) {
            log.info("flushYesterday {}", target);
            return;
        }

        // company + 날짜 기준 upsert
        counts.forEach((company, count) -> {
            companyRankDailyRepository.upsert(target, company, count);
        });

        // 이관 완료 후 Redis 데이터 삭제 (중복 방지)
        redisRankingService.delete(target);
        log.info("flushYesterday {}", target);
    }
}
