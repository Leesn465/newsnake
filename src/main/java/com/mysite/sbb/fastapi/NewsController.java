package com.mysite.sbb.fastapi;

import com.mysite.sbb.fastapi.Kafka.AnalysisResultStore;
import com.mysite.sbb.fastapi.Kafka.AnalyzeFetchAndSave;
import com.mysite.sbb.fastapi.Kafka.AnalyzeKafkaProducer;
import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeAsyncRequestDTO;
import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeAsyncResponseDTO;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Slice;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class NewsController {


    private final RestTemplate restTemplate;
    private final FastApiService fastApiService;
    private final AnalyzeKafkaProducer kafkaProducer;
    private final AnalyzeFetchAndSave analyzeFetchAndSave;
    private final AnalysisResultStore analysisResultStore;
    private final UserService userService;

    //    @PostMapping("/parse-news")
//    public FastApiResponse fetchNewsFromFastAPI(@RequestBody FastApiDTO body) {
//        return fastApiService.fetchAndSaveNews(body);
//    }
    //    @MessageMapping("/analyze")
//    public void analyzeNews(@Payload FastApiDTO request) {
//        System.out.println("📨 파싱됨: url=" + request.getUrl() + ", id=" + request.getId());
//        fastApiService.analyzeNewsStepWS(request.getUrl(), request.getId());
//    }
    @PostMapping("/analyze-async")
    public ResponseEntity<AnalyzeAsyncResponseDTO> analyzeAsync(@RequestBody AnalyzeAsyncRequestDTO req) {


        return ResponseEntity.ok(analyzeFetchAndSave.start(req.url(), req.id()));
    }

    @GetMapping("/analyze-result/{analysisId}")
    public ResponseEntity<?> getAnalyzeResult(@PathVariable String analysisId) {
        FastApiResponse result = analysisResultStore.getResult(analysisId);
        if (result == null) {
            return ResponseEntity.status(404).body(Map.of("status", "PENDING"));
        }
        return ResponseEntity.ok(result);
    }


    @GetMapping("/ranking")
    public ResponseEntity<List<CompanyRankDto>> getCompanyRanking(@RequestParam("period") String period) {
        List<CompanyRankDto> result;
        switch (period) {
            case "daily":
                result = fastApiService.getDailyRanking();
                break;
            case "weekly":

                result = fastApiService.getWeeklyRanking();
                break;
            case "monthly":
                result = fastApiService.getMonthlyRanking();
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid period");
        }
        return ResponseEntity.ok(result);
    }

    //    @GetMapping("/posts")
//    public ResponseEntity<Page<FastApiEntity>> getPosts(
//            Principal principal,
//            @PageableDefault(sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
//
//        if(principal == null) {
//            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Principal is null");
//        }
//        String username = principal.getName();
//        Page<FastApiEntity> posts = fastApiService.getUserById(username, pageable);
//        return ResponseEntity.ok(posts);
//    }
    @GetMapping("/posts/seek")
    public ResponseEntity<Slice<FastApiEntity>> getPostsSeek(
            Principal principal,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 안 되어 있음");
        }
        String username = principal.getName();
        SiteUser siteUser = userService.findByUsername(username); // 서비스 통해 조회

        if (siteUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "존재하지 않는 사용자");
        }

        Slice<FastApiEntity> slice =
                fastApiService.getUserPostsSeek(siteUser.getId(), lastId, size);
        return ResponseEntity.ok(slice);
    }


//    @GetMapping("/company-posts")
//    public ResponseEntity<Page<FastApiEntity>> getCompanyPosts(
//            @RequestParam("company") String company,
//            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
//
//        Page<FastApiEntity> posts = fastApiService.getCompanyRecords(company, pageable);
//        return ResponseEntity.ok(posts);
//    }

    @GetMapping("/company-posts/seek")
    public ResponseEntity<SeekSliceResponse<FastApiEntity>> getCompanyPostsSeek(
            @RequestParam("company") String company,
            @RequestParam(value = "lastCreatedAt", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastCreatedAt,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(fastApiService.getCompanyRecordsSeek(company, lastCreatedAt, lastId, size));
    }


    @GetMapping("/stock-data")
    public ResponseEntity<Object> getStockData(@RequestParam("company") String company) {

        if (company == null || company.isBlank() || "undefined".equals(company)) {
            return ResponseEntity.badRequest().body("company query param is required");
        }

        // React에서 받은 값 그대로 FastAPI로 전달
        String url = "https://leesn465-fastapi-stock-api.hf.space/ai/stock-data/by-name" + "?company_name=" + company;


        ResponseEntity<Object> response = restTemplate.getForEntity(url, Object.class);

        // FastAPI 응답 그대로 반환
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }


}