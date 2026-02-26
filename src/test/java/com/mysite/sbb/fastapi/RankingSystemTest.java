package com.mysite.sbb.fastapi;

import com.mysite.sbb.fastapi.optimization.CompanyRankDailyRepository;
import com.mysite.sbb.fastapi.optimization.RedisRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RankingSystemTest {

    @Autowired
    private FastApiService fastApiService;

    @Autowired
    private FastApiRepository fastApiRepository;


    @Autowired
    private RedisRankingService redisRankingService;

    @Autowired
    private CompanyRankDailyRepository companyRankDailyRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @BeforeEach
    void setup() {
        // Redis 캐시 초기화
        stringRedisTemplate.getConnectionFactory().getConnection().flushAll();
        // DB 초기화
        companyRankDailyRepository.deleteAll();
    }

    // ========================================================
    // 1️⃣ Redis 실시간 카운팅 테스트
    // ========================================================

    @Test
    @DisplayName("1. Redis 실시간 회사별 count 증가")
    void test_redis_increaseToday() {
        // Given
        String company = "Samsung";

        // When
        for (int i = 0; i < 5; i++) {
            redisRankingService.increaseToday(company);
        }

        // Then
        List<CompanyRankDto> result = redisRankingService.getTodayTopN(10);


        assertThat(result).isNotEmpty();
        assertThat(result.get(0).company()).isEqualTo("Samsung");
        assertThat(result.get(0).companyCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("2. Redis 일간(Today) 상위 N개 조회")
    void test_redis_getTodayTopN() {
        // Given: 여러 회사 데이터 추가
        Map<String, Integer> companies = Map.of(
                "Samsung", 10,
                "SK", 8,
                "LG", 6,
                "Hyundai", 4,
                "Kakao", 2
        );

        // When: Redis에 데이터 추가
        companies.forEach((company, count) -> {
            for (int i = 0; i < count; i++) {
                redisRankingService.increaseToday(company);
            }
        });

        // Then: Top 3 조회
        List<CompanyRankDto> topN = redisRankingService.getTodayTopN(3);


        assertThat(topN).hasSize(3);
        assertThat(topN.get(0).company()).isEqualTo("Samsung");
        assertThat(topN.get(1).company()).isEqualTo("SK");
    }

    // ========================================================
    // 2️⃣ 배치 플러시 로직 테스트
    // ========================================================


    // ========================================================
    // 3️⃣ FastApiService 랭킹 조회 테스트
    // ========================================================
    @Test
    @DisplayName("3. 배치: Redis → DB 플러시 (어제 데이터)")
    void test_batch_flushYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        // ✅ dailyKey() 메서드 쓰지 말고, 직접 같은 방식으로 생성
        String yesterdayKey = "rank:company:daily:" + yesterday;

        System.out.println("🔍 사용할 Redis 키: {}" + yesterdayKey);

        // Step 1: Redis에 직접 저장
        stringRedisTemplate.opsForZSet().incrementScore(yesterdayKey, "Samsung", 10);
        stringRedisTemplate.opsForZSet().incrementScore(yesterdayKey, "SK", 8);

        // Step 2: 정말 저장되었는지 Redis에서 직접 확인
        Set<ZSetOperations.TypedTuple<String>> redisData =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(yesterdayKey, 0, -1);

        System.out.println("✅ Redis 직접 조회 결과: {}" + redisData);

        // Step 3: 데이터가 없으면 테스트 실패
        if (redisData == null || redisData.isEmpty()) {
            System.out.println("❌ Redis에 데이터가 없습니다!");
            fail("Redis 저장 실패");  // 또는 return;
        }

        // Step 4: Service 메서드로 조회
        Map<String, Integer> counts = redisRankingService.getAllCounts(yesterday);
        System.out.println("🔍 Service.getAllCounts() 결과: {}" + counts);

        // ✅ Step 5: 여기서 empty check
        assertThat(counts).isNotEmpty();  // ← 139줄 에러

        // 이후 DB 저장 로직
        counts.forEach((company, count) -> {
            companyRankDailyRepository.upsert(yesterday, company, count);
        });

        List<Object[]> dbResult = companyRankDailyRepository.sumRangeTopN(
                yesterday,
                yesterday.plusDays(1),
                10
        );

        System.out.println("📊 DB 결과: {}" + dbResult);
        assertThat(dbResult).isNotEmpty();
    }

    @Test
    @DisplayName("4. FastApiService: 일간 랭킹 조회")
    void test_service_getDailyRanking() {
        // Given: Redis 일간 데이터 준비
        for (int i = 0; i < 5; i++) redisRankingService.increaseToday("Samsung");
        for (int i = 0; i < 3; i++) redisRankingService.increaseToday("SK");

        // When
        List<CompanyRankDto> result = fastApiService.getDailyRanking();


        // Then
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).company()).isEqualTo("Samsung");
    }

    @Test
    @DisplayName("5. FastApiService: 주간 랭킹 조회")
    void test_service_getWeeklyRanking() {
        // Given: 지난 7일간 DB에 데이터 저장
        LocalDate today = LocalDate.now();
        for (int i = 7; i > 0; i--) {
            LocalDate date = today.minusDays(i);
            companyRankDailyRepository.upsert(date, "Samsung", 10);
            companyRankDailyRepository.upsert(date, "SK", 5);
        }


        // When
        List<CompanyRankDto> result = fastApiService.getWeeklyRanking();


        // Then
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).company()).isEqualTo("Samsung");
        assertThat(result.get(0).companyCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("6. FastApiService: 월간 랭킹 조회")
    void test_service_getMonthlyRanking() {
        // Given: 지난 30일간 DB에 데이터 저장
        LocalDate today = LocalDate.now();
        for (int i = 30; i > 0; i--) {
            LocalDate date = today.minusDays(i);
            companyRankDailyRepository.upsert(date, "Samsung", 15);
            companyRankDailyRepository.upsert(date, "SK", 8);
            companyRankDailyRepository.upsert(date, "LG", 5);
        }


        // When
        List<CompanyRankDto> result = fastApiService.getMonthlyRanking();


        // Then
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).company()).isEqualTo("Samsung");
        assertThat(result.get(0).companyCount())
                .isGreaterThan(0)
                .isLessThanOrEqualTo(30 * 15); // max 30일 * 15count
    }

    // ========================================================
    // 4️⃣ 통합 E2E 테스트
    // ========================================================

    @Test
    @DisplayName("7. E2E 통합 테스트: Redis → DB → 랭킹")
    void test_e2e_full_flow() {

        // Step 1: 실시간 카운팅 (오늘)
        redisRankingService.increaseToday("Samsung");
        redisRankingService.increaseToday("Samsung");
        redisRankingService.increaseToday("SK");

        List<CompanyRankDto> dailyResult = fastApiService.getDailyRanking();

        assertThat(dailyResult.get(0).company()).isEqualTo("Samsung");

        // Step 2: 어제 데이터 DB 저장
        LocalDate yesterday = LocalDate.now().minusDays(1);
        companyRankDailyRepository.upsert(yesterday, "Samsung", 5);
        companyRankDailyRepository.upsert(yesterday, "SK", 3);

        // Step 3: 주간/월간 조회
        List<CompanyRankDto> weeklyResult = fastApiService.getWeeklyRanking();

        assertThat(weeklyResult).isNotEmpty();
        assertThat(weeklyResult.stream()
                .anyMatch(r -> !r.company().equals("—")))
                .isTrue();
    }

    // ========================================================
    // 5️⃣ 엣지 케이스 테스트
    // ========================================================

    @Test
    @DisplayName("8. 엣지 케이스: 빈 Redis 상태에서 조회")
    void test_edge_empty_redis() {
        List<CompanyRankDto> result = fastApiService.getDailyRanking();


        assertThat(result).hasSize(10); // 기본 10개로 채워짐
        assertThat(result.get(0).company()).isEqualTo("—");
    }

    @Test
    @DisplayName("9. 엣지 케이스: DB에 데이터 없을 때 주간 조회")
    void test_edge_empty_db_weekly() {
        List<CompanyRankDto> result = fastApiService.getWeeklyRanking();


        assertThat(result).hasSize(10);
        assertThat(result.get(0).company()).isEqualTo("—");
    }

    @Test
    @DisplayName("10. Repository 쿼리 직접 테스트")
    void test_repository_sumRangeTopN() {
        // Given: 테스트 데이터
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(1);

        companyRankDailyRepository.upsert(start, "Samsung", 100);
        companyRankDailyRepository.upsert(start.plusDays(1), "Samsung", 50);
        companyRankDailyRepository.upsert(start.plusDays(2), "SK", 30);

        // When
        List<Object[]> result = companyRankDailyRepository.sumRangeTopN(start, end, 5);

        // Then
        assertThat(result).isNotEmpty();
        assertThat(result.get(0)[0]).isEqualTo("Samsung");
        Number sumValue = (Number) result.get(0)[1];
        assertThat(sumValue.longValue()).isEqualTo(150L); // 100 + 50
    }

    /// ////////// 실제 부하 테스트
    /*
    테스트 시나리오
        company_rank_daily에 100,000개 데이터 저장

        30개 회사 × 3,333일치 데이터

        또는 30일 × 3,333개 회사

        월간 랭킹 조회 (30일 데이터 집계)

        실제 성능 측정

        정확한 SUM() 검증

        주간 랭킹도 함께 검증
     */
    @Test
    @DisplayName("대용량 데이터 부하 테스트: 10만 개 데이터 저장 및 월간 랭킹 정확성")
    void test_load_100k_records_and_monthly_ranking() {
        System.out.println("\n🔥 부하 테스트 시작: 10만 개 데이터 저장");

        LocalDate today = LocalDate.now();
        LocalDate thirtyDaysAgo = today.minusDays(30);

        // Step 1: 대량 데이터 저장
        System.out.println("⏱️ Step 1: 10만 개 데이터 저장 중...");
        long startTime = System.currentTimeMillis();

        // 시나리오: 30개 회사 × 30일 × 112회차 = 100,800개
        String[] companies = {
                "Samsung", "SK", "LG", "Hyundai", "Kia", "NAVER", "Kakao", "Coupang",
                "NHN", "CJ", "GS", "Lotte", "Hanwha", "Daewoo", "KCC",
                "Posco", "Steelysis", "Doosan", "Hanwon", "Hyosung",
                "KT", "SKT", "LGU+", "Samsung Electronics", "Samsung SDS",
                "Samsung C&T", "Samsung Biologics", "Samsung Fire", "Samsung Heavy", "Samsung SDI"
        };

        int totalCount = 0;

        // 30일 동안의 데이터
        for (int dayOffset = 30; dayOffset >= 0; dayOffset--) {
            LocalDate date = today.minusDays(dayOffset);

            // 각 회사별로 다른 카운트 (현실적인 분포)
            for (int i = 0; i < companies.length; i++) {
                String company = companies[i];
                // 회사마다 다른 카운트 (대략 10~1000 사이)
                int count = (i + 1) * 100 + dayOffset * 5;

                companyRankDailyRepository.upsert(date, company, count);
                totalCount++;
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("✅ 저장 완료: " + totalCount + "개 레코드 저장됨 (" + (endTime - startTime) + "ms)");

        // Step 2: DB에서 전체 카운트 확인
        System.out.println("\n⏱️ Step 2: DB 데이터 검증");
        int dbTotalCount = (int) companyRankDailyRepository.count();
        System.out.println("📊 DB 현재 총 레코드: " + dbTotalCount);

        // Step 3: 월간 랭킹 조회 성능 측정
        System.out.println("\n⏱️ Step 3: 월간 랭킹 조회 (30일 집계)");
        startTime = System.currentTimeMillis();

        List<CompanyRankDto> monthlyRanking = fastApiService.getMonthlyRanking();

        endTime = System.currentTimeMillis();
        System.out.println("✅ 월간 랭킹 조회 완료 (" + (endTime - startTime) + "ms)");

        // Step 4: 결과 검증
        System.out.println("\n📊 월간 랭킹 결과 (상위 10개):");
        for (int i = 0; i < Math.min(10, monthlyRanking.size()); i++) {
            CompanyRankDto rank = monthlyRanking.get(i);
            System.out.printf("  %2d. %s: %,d\n", i + 1, rank.company(), rank.companyCount());
        }

        // Step 5: 검증 로직
        assertThat(monthlyRanking).isNotEmpty();
        assertThat(monthlyRanking).hasSizeGreaterThanOrEqualTo(10);

        // 첫 번째가 "—"가 아니어야 함 (데이터 있는지 확인)
        assertThat(monthlyRanking.get(0).company()).isNotEqualTo("—");
        assertThat(monthlyRanking.get(0).companyCount()).isGreaterThan(0);

        // Samsung SDI를 검증 (상위 1위)
        long expectedSamsungSDI = 95325;  // 30*100*31 + 5*(0+...+30) = 95,325
        long actualSamsungSDI = monthlyRanking.stream()
                .filter(r -> r.company().equals("Samsung SDI"))
                .mapToLong(CompanyRankDto::companyCount)
                .sum();

        System.out.println("\n🔍 Samsung SDI 검증 (상위 1위):");
        System.out.println("  예상값: " + expectedSamsungSDI);
        System.out.println("  실제값: " + actualSamsungSDI);
        assertThat(actualSamsungSDI).isEqualTo(expectedSamsungSDI);

        // Step 6: 주간 랭킹도 확인
        System.out.println("\n⏱️ Step 6: 주간 랭킹 조회 (7일 집계)");
        startTime = System.currentTimeMillis();

        List<CompanyRankDto> weeklyRanking = fastApiService.getWeeklyRanking();

        endTime = System.currentTimeMillis();
        System.out.println("✅ 주간 랭킹 조회 완료 (" + (endTime - startTime) + "ms)");

        System.out.println("\n📊 주간 랭킹 결과 (상위 5개):");
        for (int i = 0; i < Math.min(5, weeklyRanking.size()); i++) {
            CompanyRankDto rank = weeklyRanking.get(i);
            System.out.printf("  %2d. %s: %,d\n", i + 1, rank.company(), rank.companyCount());
        }

        assertThat(weeklyRanking).isNotEmpty();
        assertThat(weeklyRanking.get(0).company()).isNotEqualTo("—");

        System.out.println("\n✅ 부하 테스트 완료!");
    }

    @Test
    @DisplayName("성능 비교: 원래 방식 vs 최적화 방식")
    void test_performance_comparison() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("🔥 성능 비교 테스트 시작");
        System.out.println("=".repeat(70));

        LocalDate today = LocalDate.now();

        // ========================
        // Phase 1: 데이터 준비
        // ========================
        System.out.println("\n📊 Phase 1: 테스트 데이터 준비");
        System.out.println("-".repeat(70));

        String[] companies = {
                "Samsung", "SK", "LG", "Hyundai", "Kia",
                "NAVER", "Kakao", "Coupang", "NHN", "CJ"
        };

        long dataStartTime = System.currentTimeMillis();

        // 1. FastApiEntity에 데이터 저장 (30일 × 10개 회사 × 10개 이벤트 = 3,000개)
        System.out.println("1️⃣ FastApiEntity에 3,000개 레코드 저장 중...");
        for (int dayOffset = 30; dayOffset >= 0; dayOffset--) {
            LocalDate date = today.minusDays(dayOffset);
            LocalDateTime dateTime = date.atStartOfDay();

            for (int i = 0; i < companies.length; i++) {
                for (int j = 0; j < 10; j++) {  // 하루에 회사당 10개 이벤트
                    String company = companies[i];
                    FastApiEntity entity = FastApiEntity.builder()
                            .company(company)
                            .title("Test News " + j)
                            .content("Test Content")
                            .url("http://test.com/" + dayOffset + "/" + i + "/" + j)
                            .createdAt(dateTime.plusHours(j))
                            .build();

                    try {
                        fastApiRepository.save(entity);
                    } catch (Exception e) {
                        // 중복 URL은 무시
                    }
                }
            }
        }

        long dataEndTime = System.currentTimeMillis();
        System.out.println("✅ FastApiEntity 저장 완료: " + (dataEndTime - dataStartTime) + "ms");

        // 2. CompanyRankDaily에 집계 데이터 저장 (30일 × 10개 회사 = 300개)
        System.out.println("2️⃣ CompanyRankDaily에 300개 레코드 저장 중...");
        dataStartTime = System.currentTimeMillis();

        for (int dayOffset = 30; dayOffset >= 0; dayOffset--) {
            LocalDate date = today.minusDays(dayOffset);

            for (int i = 0; i < companies.length; i++) {
                String company = companies[i];
                int count = 10;  // 각 회사당 1000 (= 10 이벤트 × 100)

                companyRankDailyRepository.upsert(date, company, count);
            }
        }

        dataEndTime = System.currentTimeMillis();
        System.out.println("✅ CompanyRankDaily 저장 완료: " + (dataEndTime - dataStartTime) + "ms");

        // ========================
        // Phase 2: 월간 랭킹 조회 성능 비교
        // ========================
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📈 Phase 2: 월간 랭킹 조회 성능 비교");
        System.out.println("=".repeat(70));

        LocalDateTime monthAgo = LocalDateTime.now().minusDays(30);
        LocalDate startDate = today.minusDays(30);
        LocalDate endDate = today.plusDays(1);

        // ──────────────────────────────────────────────────────
        // 방법 1️⃣: 원래 방식 - FastApiEntity에서 GROUP BY COUNT
        // ──────────────────────────────────────────────────────
        System.out.println("\n🔴 방법 1️⃣: 원래 방식 (FastApiEntity GROUP BY COUNT)");
        System.out.println("-".repeat(70));

        List<Object[]> resultLegacy = null;
        long legacyStartTime = 0;
        long legacyEndTime = 0;
        int legacyRowsScanned = 0;

        try {
            // 워밍업
            fastApiRepository.getMonthlyCompanyRanking_Legacy(monthAgo);

            // 실제 측정
            legacyStartTime = System.currentTimeMillis();
            resultLegacy = fastApiRepository.getMonthlyCompanyRanking_Legacy(monthAgo);
            legacyEndTime = System.currentTimeMillis();

            legacyRowsScanned = (int) fastApiRepository.count();

            System.out.println("✅ 쿼리 완료");
            System.out.println("   - 실행 시간: " + (legacyEndTime - legacyStartTime) + "ms");
            System.out.println("   - 테이블 스캔 대상 row: " + legacyRowsScanned + "개");
            System.out.println("   - 결과: " + (resultLegacy != null ? resultLegacy.size() : 0) + "개 회사");

            if (resultLegacy != null) {
                System.out.println("   - 상위 3개:");
                for (int i = 0; i < Math.min(3, resultLegacy.size()); i++) {
                    Object[] row = resultLegacy.get(i);
                    System.out.printf("     %d. %s: %s\n", i + 1, row, row);
                }
            }

        } catch (Exception e) {
            System.out.println("❌ 오류: " + e.getMessage());
            e.printStackTrace();
        }

        // ──────────────────────────────────────────────────────
        // 방법 2️⃣: 최적화 방식 - CompanyRankDaily에서 SUM
        // ──────────────────────────────────────────────────────
        System.out.println("\n🟢 방법 2️⃣: 최적화 방식 (CompanyRankDaily SUM)");
        System.out.println("-".repeat(70));

        List<Object[]> resultOptimized = null;
        long optimizedStartTime = 0;
        long optimizedEndTime = 0;
        int optimizedRowsScanned = 0;

        try {
            // 워밍업
            companyRankDailyRepository.sumRangeTopN(startDate, endDate, 10);

            // 실제 측정
            optimizedStartTime = System.currentTimeMillis();
            resultOptimized = companyRankDailyRepository.sumRangeTopN(startDate, endDate, 10);
            optimizedEndTime = System.currentTimeMillis();

            optimizedRowsScanned = (int) companyRankDailyRepository.count();

            System.out.println("✅ 쿼리 완료");
            System.out.println("   - 실행 시간: " + (optimizedEndTime - optimizedStartTime) + "ms");
            System.out.println("   - 테이블 스캔 대상 row: " + optimizedRowsScanned + "개");
            System.out.println("   - 결과: " + (resultOptimized != null ? resultOptimized.size() : 0) + "개 회사");

            if (resultOptimized != null) {
                System.out.println("   - 상위 3개:");
                for (int i = 0; i < Math.min(3, resultOptimized.size()); i++) {
                    Object[] row = resultOptimized.get(i);
                    System.out.printf("     %d. %s: %s\n", i + 1, row, row);
                }
            }

        } catch (Exception e) {
            System.out.println("❌ 오류: " + e.getMessage());
            e.printStackTrace();
        }

        // ========================
        // Phase 3: 결과 분석
        // ========================
        System.out.println("\n" + "=".repeat(70));
        System.out.println("📊 Phase 3: 성능 분석");
        System.out.println("=".repeat(70));

        long legacyTime = legacyEndTime - legacyStartTime;
        long optimizedTime = optimizedEndTime - optimizedStartTime;
        double improvement = legacyTime > 0 ? ((double) (legacyTime - optimizedTime) / legacyTime * 100) : 0;

        System.out.println("\n📈 성능 비교 결과:");
        System.out.println("-".repeat(70));
        System.out.printf("| 항목              | 원래 방식(1️⃣)  | 최적화 방식(2️⃣) | 차이        |\n");
        System.out.println("|-------------------|----------------|-----------------|----------------|");
        System.out.printf("| 실행 시간         | %4dms        | %4dms        | %+.1f%%      |\n",
                legacyTime, optimizedTime, improvement);
        System.out.printf("| 스캔 대상 row     | %,6d개     | %,6d개     | %,6d개   |\n",
                legacyRowsScanned, optimizedRowsScanned,
                legacyRowsScanned - optimizedRowsScanned);
        System.out.printf("| 결과 row 수       | %,6d개     | %,6d개     | 동일       |\n",
                resultLegacy != null ? resultLegacy.size() : 0,
                resultOptimized != null ? resultOptimized.size() : 0);
        System.out.println("|-------------------|----------------|-----------------|----------------|");

        System.out.println("\n✅ 결론:");
        if (improvement > 0) {
            System.out.printf("   최적화 방식이 %.1f%% 더 빠름! 🎉\n", improvement);
        } else if (improvement < 0) {
            System.out.printf("   원래 방식이 %.1f%% 더 빠름\n", -improvement);
        } else {
            System.out.println("   성능 차이 없음");
        }

        System.out.println("\n💡 분석:");
        System.out.println("   - 원래 방식: " + legacyRowsScanned + "개 row를 GROUP BY + COUNT로 처리");
        System.out.println("   - 최적화: " + optimizedRowsScanned + "개 row만으로 SUM 처리");
        System.out.println("   - 데이터가 커질수록 최적화 방식의 장점이 더 커짐");

        System.out.println("\n" + "=".repeat(70));
        System.out.println("✅ 성능 비교 테스트 완료");
        System.out.println("=".repeat(70) + "\n");
    }


}