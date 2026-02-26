package com.mysite.sbb.fastapi;

import com.mysite.sbb.fastapi.optimization.CompanyRankDailyRepository;
import com.mysite.sbb.fastapi.optimization.RedisRankingService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FastAPI 기반 AI 분석 서버와의 연동 및
 * 뉴스 데이터 저장, 랭킹 집계, 페이징 최적화를 담당하는 서비스.
 * <p>
 * 설계 목적:
 * 1. ML 연산 서버(FastAPI)와 Web 서버(Spring)를 분리하여 역할 분리
 * 2. Kafka 기반 비동기 분석 처리로 사용자 대기 시간 최소화
 * 3. Redis를 활용한 실시간 랭킹 집계 성능 최적화
 * 4. Seek Pagination을 적용하여 대용량 데이터에서 성능 유지
 * <p>
 * 단순 CRUD 서비스가 아닌,
 * 외부 AI 서비스 연동 + 캐시 전략 + 랭킹 집계 전략을 포함한
 * 확장형 서비스 레이어이다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FastApiService {


    private final FastApiRepository fastApiRepository;
    private final UserService userService;
    private final RestTemplate restTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisRankingService redisRankingService;
    private final CompanyRankDailyRepository companyRankDailyRepository;


//    public Page<FastApiEntity> getUserById(String username, Pageable pageable) {
//        Page<FastApiEntity> posts = fastApiRepository.findByUser_Username(username, pageable);
//        if (posts.isEmpty()) throw new PostNotFoundException("해당 유저의 게시글이 없습니다.");
//        return posts;
//    }

    public String currentWeekKey() {
        LocalDate now = LocalDate.now();
        int week = now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        int year = now.get(java.time.temporal.WeekFields.ISO.weekBasedYear());
        return year + "-W" + week;   // 예: 2026-W6
    }

    public String currentMonthKey() {
        YearMonth ym = YearMonth.now();
        return ym.toString();       // 예: 2026-02
    }

    /**
     * Offset 기반이 아닌 Seek Pagination 방식으로
     * 사용자의 뉴스 기록을 조회한다.
     * <p>
     * - lastId 기준으로 다음 데이터를 조회
     * <p>
     * - COUNT 쿼리를 수행하지 않으므로 Deep Paging 환경에서 성능 우수
     * <p>
     * - size + 1 조회 후 hasNext 여부 판단
     */
    public Slice<FastApiEntity> getUserPostsSeek(Long userId, Long lastId, int size) {
        // size+1개를 가져와서 다음 페이지 존재 여부 판단
        PageRequest pr = PageRequest.of(0, size + 1);
        List<FastApiEntity> rows = fastApiRepository.findUserPostsSeek(userId, lastId, pr);

        boolean hasNext = rows.size() > size;
        if (hasNext) rows = rows.subList(0, size);
        // Slice는 total count 불필요 → deep paging에서 더 유리
        return new SliceImpl<>(rows, PageRequest.of(0, size), hasNext);
    }

    /**
     * 오늘 기준 인기 회사 랭킹 조회.
     * <p>
     * 1차: Redis에서 실시간 집계 데이터 조회 (O(1))
     * 2차: Redis 데이터가 없을 경우 DB 집계 결과로 fallback
     * <p>
     * → 캐시 우선 전략(Cache-Aside Pattern)
     */

    public List<CompanyRankDto> getDailyRanking() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfToday = today.atStartOfDay();
        LocalDateTime startOfTomorrow = today.plusDays(1).atStartOfDay();

        List<CompanyRankDto> fromRedis = redisRankingService.getTodayTopN(10);
        if (!fromRedis.isEmpty()) return fromRedis;

        // fallback: 기존 daily DB 집계 (너 코드 유지 가능)
        return convertResult(fastApiRepository.getDailyCompanyRanking(startOfToday, startOfTomorrow));
    }

    /**
     * 주간 랭킹 조회.
     * <p>
     * Spring Cache를 활용하여
     * 동일 기간 요청에 대해 DB 재조회 방지.
     * <p>
     * key는 ISO week 기반으로 생성.
     */

    @Cacheable(cacheNames = "rankWeekly", key = "#root.target.currentWeekKey()")
    public List<CompanyRankDto> getWeeklyRanking() {
        LocalDate end = LocalDate.now().plusDays(1);   // [start, end)
        LocalDate start = LocalDate.now().minusDays(7);
        return convertSum(companyRankDailyRepository.sumRangeTopN(start, end, 10));
    }

    @Cacheable(cacheNames = "rankMonthly", key = "#root.target.currentMonthKey()")
    public List<CompanyRankDto> getMonthlyRanking() {
        LocalDate end = LocalDate.now().plusDays(1);
        LocalDate start = LocalDate.now().minusDays(30);
        return convertSum(companyRankDailyRepository.sumRangeTopN(start, end, 10));
    }

    private List<CompanyRankDto> convertSum(List<Object[]> rows) {
        List<CompanyRankDto> list = rows.stream()
                .map(r -> new CompanyRankDto((String) r[0], ((Number) r[1]).longValue()))
                .toList();
        List<CompanyRankDto> out = new ArrayList<>(list);
        while (out.size() < 10) out.add(new CompanyRankDto("—", 0L));
        return out;
    }


    private List<CompanyRankDto> convertResult(List<Object[]> results) {
        List<CompanyRankDto> rankList = results.stream()
                .map(obj -> new CompanyRankDto((String) obj[0], (Long) obj[1]))
                .collect(Collectors.toList());
        while (rankList.size() < 10)
            rankList.add(new CompanyRankDto("—", 0L));
        return rankList;
    }

//    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
//    public FastApiResponse fetchAndSaveNews(FastApiDTO body) {
//        SiteUser user = userService.findByUsername(body.getId());
//        FastApiResponse apiResponse;
//
//        // 1. 중복된 뉴스 기사 확인
//        FastApiEntity findEntity = fastApiRepository.findFirstByUrl(body.getUrl());
//
//        System.out.println("찾은 엔티티: " + findEntity);
//
//        if (findEntity != null) {
//            apiResponse = findEntity.toResponse();
//            System.out.println("이미 존재하는 기사 반환: " + apiResponse);
//            if (fastApiRepository.existsByUserAndUrl(user, body.getUrl())) {
//                return apiResponse;
//            }
//        } else {
//            // 2. FastAPI 호출
//            String fastApiUrl = "https://leesn465-fastapi-stock-api.hf.space/ai/parse-news";
//            HttpHeaders headers = new HttpHeaders();
//            headers.setContentType(MediaType.APPLICATION_JSON);
//            String jsonBody = String.format("{\"url\": \"%s\", \"id\": \"%s\"}", body.getUrl(), body.getId());
//            HttpEntity<String> httpRequest = new HttpEntity<>(jsonBody, headers);
//            ResponseEntity<FastApiResponse> response = restTemplate.postForEntity(fastApiUrl, httpRequest, FastApiResponse.class);
//            apiResponse = response.getBody();
//            System.out.println("FastAPI 응답: " + apiResponse);
//
//            if (apiResponse == null) throw new RuntimeException("FastAPI 응답이 없습니다.");
//        }
//
//        // 중복 체크 후 저장
//        if (!fastApiRepository.existsByUserAndUrl(user, body.getUrl())) {
//            try {
//                this.saveEntity(user, apiResponse);  // 데이터 저장
//            } catch (UnexpectedRollbackException e) {
//                System.err.println("⚠️ 중복된 뉴스 기사(사용자-URL)가 발견되어 저장을 건너뜁니다.");
//            }
//        }
//
//        return apiResponse;
//    }

    /**
     * FastAPI 분석 결과를 DB에 저장한다.
     * <p>
     * - REQUIRES_NEW 전파 옵션 사용
     * → 외부 트랜잭션과 분리하여 독립 커밋 보장
     * <p>
     * - (user + url) 기준 중복 방지 <p>
     * - 저장 성공 시 Redis 랭킹 점수 증가 <p>
     * - 사용자별 저장 개수 제한 적용 <p>
     * <p>
     * 단순 저장이 아니라
     * 중복 방지 + 랭킹 집계 + 데이터 정리 정책을 포함한 메서드이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveEntity(SiteUser user, FastApiResponse apiResponse) {

        String url = apiResponse.url();

        // 1) 중복 체크
        if (user != null) {
            if (fastApiRepository.existsByUser_IdAndUrl(user.getId(), url)) {
                log.info("중복(유저-URL)이라 저장 스킵: userId={}, url={}", user.getId(), url);
                return;
            }
        } else {
            if (fastApiRepository.existsByUserIsNullAndUrl(url)) {
                log.info("중복(비로그인-URL)이라 저장 스킵: url={}", url);
                return;
            }
        }

        FastApiEntity entity = FastApiEntity.builder()
                .user(user)
                .title(apiResponse.title())
                .content(apiResponse.content())
                .url(url)
                .thumbnailUrl(apiResponse.thumbnail_url())
                .newsTime(apiResponse.time())
                .company(apiResponse.company())
                .summary(apiResponse.summary())
                .prediction(apiResponse.prediction())
                .keywords(apiResponse.keyword())
                .sentiment(apiResponse.sentiment())
                .prob(apiResponse.prob())
                .build();

        fastApiRepository.save(entity);
        redisRankingService.increaseToday(entity.getCompany());

        if (user != null) {
            limitUserNewsRecords(user);
        }
    }

    /**
     * 사용자별 뉴스 저장 개수를 제한한다.
     * <p>
     * 데이터 무한 증가 방지 목적.
     * 최대 30개 유지 후 초과분은 오래된 순으로 삭제.
     * <p>
     * → Storage Cost & 조회 성능 관리 전략
     */

    @Transactional
    public void limitUserNewsRecords(SiteUser user) {
        long total = fastApiRepository.countByUser(user);
        int MAXRECODE = 30;
        if (total > MAXRECODE) {
            int toDelete = (int) (total - MAXRECODE);
            Pageable pageable = PageRequest.of(0, toDelete);
            List<FastApiEntity> oldest = fastApiRepository.findByUserOrderByCreatedAtAsc(user, pageable);
            if (!oldest.isEmpty()) {
                List<Long> ids = oldest.stream().map(FastApiEntity::getId).collect(Collectors.toList());
                fastApiRepository.deleteByIdIn(ids);
            }
        }
    }

    /**
     * 특정 회사 뉴스 기록을 Seek 방식으로 조회.
     * <p>
     * createdAt + id 복합 조건을 사용하여
     * 안정적인 커서 기반 페이징 구현.
     * <p>
     * 무한 스크롤 환경에 적합한 구조.
     */
    public SeekSliceResponse<FastApiEntity> getCompanyRecordsSeek(
            String company,
            LocalDateTime lastCreatedAt,
            Long lastId,
            int size
    ) {
        Pageable pageable = PageRequest.of(0, size + 1); // hasNext 판별용으로 +1
        List<FastApiEntity> list =
                fastApiRepository.findLatestUniqueUrlByCompanySeek(company, lastCreatedAt, lastId, pageable);


        boolean hasNext = list.size() > size;
        if (hasNext) list = list.subList(0, size);


        return new SeekSliceResponse<>(list, hasNext);
    }

    //    // ========================================================
//    // WebSocket 기반 단계별 분석
//    // ========================================================
//    public void analyzeNewsStepWS(String url, String id) {
//        int progress = 0;
//        SiteUser user = userService.findByUsername(id);
//        FastApiEntity existing = fastApiRepository.findFirstByUrl(url);
//
//        if (existing != null && fastApiRepository.existsByUserAndUrl(user, url)) {
//            log.info(" DB에 기존 분석 결과 존재 → FastAPI 통신 생략");
//            messagingTemplate.convertAndSend("/topic/analyze-complete", existing.toResponse());
//            sendMessage("이미 분석된 뉴스입니다! 기존 데이터 반환!");
//            return; // FastAPI 통신 완전히 생략
//        }
//        try {
//            // 1️⃣ 요약
//            sendMessage("요약 중...");
//            Map<String, Object> summary = postJson(BASE_URL + "/summary", Map.of("url", url));
//            progress += 20;
//            sendProgress(progress);
//
//            // 2️⃣ 키워드
//            sendMessage("키워드 추출 중...");
//            Map<String, Object> keywords = postJson(BASE_URL + "/keywords",
//                    Map.of("summary", summary.get("summary")));
//            progress += 20;
//            sendProgress(progress);
//
//            // 3️⃣ 회사
//            sendMessage("관련 회사 분석 중...");
//            Map<String, Object> company = postJson(BASE_URL + "/company",
//                    Map.of("summary", summary.get("summary")));
//            progress += 20;
//            sendProgress(progress);
//
//            // 4️⃣ 감정
//            sendMessage("감정 분석 중...");
//            Map<String, Object> sentiment = postJson(BASE_URL + "/sentiment",
//                    Map.of("content", summary.get("content")));
//            progress += 20;
//            sendProgress(progress);
//
//            // 5️⃣ 예측
//            sendMessage("주가 예측 중...");
//            Map<String, Object> predict = postJson(BASE_URL + "/predict",
//                    Map.of("keywords", keywords.get("keywords")));
//            progress += 20;
//            sendProgress(progress);
//
//            // ✅ 결과 합치기
//            Map<String, Object> result = new HashMap<>();
//            result.putAll(summary);
//            result.putAll(keywords);
//            result.putAll(company);
//            result.putAll(sentiment);
//            result.putAll(predict);
//
//            fetchAndSaveNews(new FastApiDTO(url, id));
//
//
//            messagingTemplate.convertAndSend("/topic/analyze-complete", result);
//            sendMessage("분석 완료!");
//
//        } catch (Exception e) {
//            e.printStackTrace(); // ✅ 콘솔에 구체적인 원인 표시
//            messagingTemplate.convertAndSend("/topic/analyze-progress",
//                    "⚠️ 오류 발생: " + e.getClass().getSimpleName() + " - " + e.getMessage());
//        }
//    }


//    private void sendMessage(String msg) {
//        messagingTemplate.convertAndSend("/topic/analyze-progress", msg);
//    }
//
//    private void sendProgress(int percent) {
//        messagingTemplate.convertAndSend("/topic/analyze-progress", percent);
//    }
//
//    private Map<String, Object> postJson(String url, Object body) {
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
//        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
//        if (response.getBody() == null)
//            throw new RuntimeException("FastAPI 응답 없음: " + url);
//        return response.getBody();
//    }
}
