package com.mysite.sbb.fastapi.optimization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;


/**
 * 회사별 일간 랭킹 데이터를 저장하는 Repository.
 * <p>
 * - 하루 단위(company + 날짜)로 집계 데이터를 저장
 * - 주간/월간 랭킹은 이 테이블을 기반으로 다시 SUM 집계
 * <p>
 * 대용량 FastApiEntity 테이블에서 매번 GROUP BY 하지 않기 위해
 * Pre-Aggregation 전략을 적용한 구조.
 */
@Repository
public interface CompanyRankDailyRepository extends JpaRepository<CompanyRankDaily, Long> {

    /**
     * 하루 단위(company + date) 랭킹 데이터 저장.
     * <p>
     * 이미 동일한 (date, company)가 존재하면 INSERT 대신 UPDATE 수행.
     * → MySQL의 ON DUPLICATE KEY UPDATE 문법 사용 (Upsert 전략)
     * <p>
     * 동시성 환경에서도 안전하게 집계 값을 갱신하기 위함.
     */
    @Modifying
    @Transactional
    @Query(value = """
                INSERT INTO company_rank_daily(stat_date, company, cnt)
                VALUES (:date, :company, :cnt)
                ON DUPLICATE KEY UPDATE cnt = :cnt
            """, nativeQuery = true)
    void upsert(@Param("date") LocalDate date,
                @Param("company") String company,
                @Param("cnt") int cnt);

    /**
     * 특정 기간(startDate ~ endDate) 동안의 회사별 누적 집계 조회.
     * <p>
     * - 일간 테이블에서 SUM(cnt) 집계
     * - GROUP BY company
     * - 내림차순 정렬 후 상위 N개만 조회
     * <p>
     * 주간/월간 랭킹 계산 시 사용됨.
     */
    @Query(value = """
                SELECT company, SUM(cnt) AS total
                FROM company_rank_daily
                WHERE stat_date >= :startDate AND stat_date < :endDate
                GROUP BY company
                ORDER BY total DESC
                LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> sumRangeTopN(@Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate,
                                @Param("limit") int limit);

}
