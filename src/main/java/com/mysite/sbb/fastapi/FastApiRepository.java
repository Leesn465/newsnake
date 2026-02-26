package com.mysite.sbb.fastapi;

import com.mysite.sbb.user.SiteUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FastAPI 분석 결과(뉴스) 데이터 접근 레이어.
 * <p>
 * - 사용자별 조회 (Seek Pagination)
 * - 회사별 랭킹 집계
 * - 중복 URL 방지
 * - 최신 뉴스 기준 dedup 조회
 * <p>
 * 성능 최적화를 위해
 * 1) Offset 기반이 아닌 Seek 기반 페이지네이션 사용
 * 2) 집계는 DB GROUP BY 사용
 * 3) 동일 URL 중 최신 레코드만 조회
 */
public interface FastApiRepository extends JpaRepository<FastApiEntity, Long> {

    /**
     * 사용자 게시글 Seek Pagination 조회.
     * <p>
     * lastId 이전 데이터만 조회하여
     * OFFSET 없이 성능 저하를 방지한다.
     * <p>
     * ORDER BY id DESC 기준
     */
    @Query("""
                    SELECT f
                    FROM FastApiEntity f
                    WHERE f.user.id = :userId
                    AND (:lastId IS NULL OR f.id < :lastId)
                    ORDER BY f.id DESC
            """)
    List<FastApiEntity> findUserPostsSeek(
            @Param("userId") Long userId,
            @Param("lastId") Long lastId,
            Pageable pageable
    );

    /**
     * 하루 동안 회사별 뉴스 개수 집계.
     * <p>
     * createdAt 범위를 기준으로 GROUP BY company 수행.
     * 실시간 Daily 랭킹 계산에 사용.
     */

    @Query("SELECT f.company, COUNT(f) " +
            "FROM FastApiEntity f " +
            "WHERE f.createdAt >= :startDate AND f.createdAt < :endDate " +
            "GROUP BY f.company " +
            "ORDER BY COUNT(f) DESC")
    List<Object[]> getDailyCompanyRanking(@Param("startDate") LocalDateTime startDate,
                                          @Param("endDate") LocalDateTime endDate);

//    @Query("SELECT f.company, COUNT(f) " +
//            "FROM FastApiEntity f " +
//            "WHERE f.createdAt >= :startDate " +
//            "GROUP BY f.company " +
//            "ORDER BY COUNT(f) DESC")
//    List<Object[]> getWeeklyCompanyRanking(@Param("startDate") LocalDateTime startDate);
//
//    @Query("SELECT f.company, COUNT(f) " +
//            "FROM FastApiEntity f " +
//            "WHERE f.createdAt >= :startDate " +
//            "GROUP BY f.company " +
//            "ORDER BY COUNT(f) DESC")
//    List<Object[]> getMonthlyCompanyRanking(@Param("startDate") LocalDateTime startDate);
//
//    // 기존 FastApiRepository 메서드 추가 (또는 테스트에서 직접 호출)
//    @Query("SELECT f.company, COUNT(f) FROM FastApiEntity f " +
//            "WHERE f.createdAt >= :startDate AND f.createdAt < :endDate " +
//            "GROUP BY f.company ORDER BY COUNT(f) DESC")
//    List<Object[]> getMonthlyCompanyRanking_Legacy(@Param("startDate") LocalDateTime startDate,
//                                                   @Param("endDate") LocalDateTime endDate);

    /**
     * 월간 집계용 레거시 쿼리.
     * (현재는 Redis + Daily 집계 합산 구조로 대체 가능)
     */
    @Query("SELECT f.company, COUNT(f) " +
            "FROM FastApiEntity f " +
            "WHERE f.createdAt >= :startDate " +
            "GROUP BY f.company " +
            "ORDER BY COUNT(f) DESC")
    List<Object[]> getMonthlyCompanyRanking_Legacy(@Param("startDate") LocalDateTime startDate);


    // 추가 user랑 url을 비교해서 있으면
    boolean existsByUserAndUrl(SiteUser user, String url);

    ///  추가 로직
    /// countByUser → 해당 사용자 뉴스 개수 확인
    ///
    /// findByUserOrderByCreatedAtAsc → 오래된 뉴스 조회
    ///
    /// deleteByIdIn → ID 리스트로 삭제
    long countByUser(SiteUser user);

    List<FastApiEntity> findByUserOrderByCreatedAtAsc(SiteUser user, Pageable pageable);

    void deleteByIdIn(List<Long> ids);

    FastApiEntity findFirstByUrl(String url);

    Optional<FastApiEntity> findTopByCompanyOrderByCreatedAt(String companyName);
//    @Query("SELECT f FROM FastApiEntity f " +
//            "WHERE f.company = :company " +
//            // f2가 f보다 더 최신이거나 (createdAt >)
//            // 시간이 같더라도 f2의 ID가 더 큰 (ID >) 레코드가 존재하지 않는 경우만 선택
//            "AND NOT EXISTS (" +
//            "   SELECT f2 FROM FastApiEntity f2 " +
//            "   WHERE f2.company = f.company " +
//            "   AND f2.url = f.url " +
//            "   AND (" +
//            "       f2.createdAt > f.createdAt OR " +
//            "       (f2.createdAt = f.createdAt AND f2.id > f.id)" +
//            "   )" +
//            ") " +
//            "ORDER BY f.createdAt DESC")
//    Page<FastApiEntity> findLatestUniqueUrlByCompany(@Param("company") String company, Pageable pageable);

    /**
     * 회사별 최신 뉴스 조회 (Seek Pagination + URL 중복 제거).
     * <p>
     * 1) createdAt, id 복합 조건으로 Seek Pagination 구현
     * 2) 동일 company + url 중 가장 최신 데이터만 선택
     * <p>
     * Deep Paging에서도 성능 저하 없이 조회 가능.
     */
    @Query("""
                SELECT f
                FROM FastApiEntity f
                WHERE f.company = :company
                AND (
                :lastCreatedAt IS NULL
                OR f.createdAt < :lastCreatedAt
                OR (f.createdAt = :lastCreatedAt AND f.id < :lastId)
                )
                AND NOT EXISTS (
                SELECT 1 FROM FastApiEntity f2
                WHERE f2.company = f.company
                AND f2.url = f.url
                AND (
                f2.createdAt > f.createdAt OR
                (f2.createdAt = f.createdAt AND f2.id > f.id)
                )
                )
                ORDER BY f.createdAt DESC, f.id DESC
            """)
    List<FastApiEntity> findLatestUniqueUrlByCompanySeek(
            @Param("company") String company,
            @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
            @Param("lastId") Long lastId,
            Pageable pageable
    );


    List<FastApiEntity> findByCompanyContainingIgnoreCase(String q);

    boolean existsByUser_IdAndUrl(Long userId, String url);

    boolean existsByUserIsNullAndUrl(String url);

}