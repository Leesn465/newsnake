package com.mysite.sbb.fastapi.Kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.fastapi.FastApiRepository;
import com.mysite.sbb.fastapi.FastApiResponse;
import com.mysite.sbb.fastapi.FastApiService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import com.mysite.sbb.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kafka Consumer
 * <p>
 * 역할:
 * 1. FastAPI에서 발행한 분석 진행/완료 이벤트를 수신
 * 2. WebSocket으로 실시간 진행률 전송
 * 3. 완료 시 DB 저장 (멱등성 보장)
 * 4. Redis를 이용해 중복 처리 방지 및 결과 캐싱
 * <p>
 * 설계 핵심:
 * - Manual Acknowledgment (수동 커밋)
 * - Redis 기반 분산 락
 * - Idempotent(멱등) 처리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaAnalysisListener {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    // DB 저장용(너 기존 로직 재사용)
    private final FastApiService fastApiService;
    private final UserService userService;
    private final AnalysisResultStore analysisResultStore;
    private final FastApiRepository fastApiRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserRepository userRepository;


    /**
     * WebSocket 진행률 전송
     */
    private void sendProgress(String analysisId, int percent, String stage, String message) {
        messagingTemplate.convertAndSend(
                "/topic/analyze-progress/" + analysisId,
                Map.of("analysisId", analysisId, "percent", percent, "stage", stage, "message", message)
        );
    }

    /**
     * WebSocket 완료 이벤트 전송
     */
    private void sendComplete(String analysisId, Object payload) {
        messagingTemplate.convertAndSend(
                "/topic/analyze-complete/" + analysisId,
                payload
        );
    }


    /**
     * ✅ 진행률 이벤트
     * FastAPI -> Kafka(analysis-progress)
     * Spring -> WebSocket(/topic/analyze-progress/{analysisId})
     */
    @KafkaListener(topics = "analysis-progress",
            groupId = "spring-progress-consumer",
            containerFactory = "progressKafkaListenerContainerFactory",
            concurrency = "2")
    public void onProgress(String message, Acknowledgment ack) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String analysisId = root.path("analysisId").asText(null);


            if (analysisId == null || analysisId.isBlank()) {
                log.warn("[progress] missing analysisId. raw={}", message);
                ack.acknowledge();
                return;
            }


            int percent = root.path("percent").asInt(0);
            String stage = root.path("stage").asText("RUNNING");
            String msg = root.path("message").asText("");


            sendProgress(analysisId, percent, stage, msg);
            ack.acknowledge();


        } catch (Exception e) {
            log.error("[progress] parse/forward error: {}", e.getMessage(), e);
            ack.acknowledge(); // progress는 포이즌이면 버림
        }
    }

    /**
     * ✅ 완료 이벤트
     * FastAPI -> Kafka(analysis-done)
     * Spring -> DB 저장 + WebSocket(/topic/analyze-complete/{analysisId})
     */
    @KafkaListener(topics = "analysis-done",
            groupId = "spring-done-consumer",
            containerFactory = "doneKafkaListenerContainerFactory",
            concurrency = "2")
    public void onDone(String message, Acknowledgment ack) {
        String analysisId = null;
        String lockKey = null;
        String processedKey = null;

        try {
            JsonNode root = objectMapper.readTree(message);
            analysisId = root.path("analysisId").asText(null);
            String userIdRaw = root.path("userId").asText(null);
            JsonNode resultNode = root.path("result");

            if (analysisId == null || resultNode.isMissingNode()) {
                log.warn("[done] invalid payload: {}", message);
                ack.acknowledge(); // 포이즌이면 버림
                return;
            }

            lockKey = "analysis:done:lock:" + analysisId;
            processedKey = "analysis:done:processed:" + analysisId;

            // 1) 이미 처리완료면: 결과 재전송(선택) 후 ack
            String processed = stringRedisTemplate.opsForValue().get(processedKey);
            if ("1".equals(processed)) {
                log.info("[done] already processed. analysisId={}", analysisId);

                // (선택) 프론트가 놓쳤을 수 있으니 Redis에 저장된 결과 다시 보내주기
                FastApiResponse cached = analysisResultStore.getResult(analysisId);
                if (cached != null) {
                    sendComplete(analysisId, cached);
                }
                ack.acknowledge();
                return;
            }

            // 2) 락 획득 (동시/중복 실행 방지)
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 30, TimeUnit.MINUTES);

            if (locked == null || !locked) {
                String processed2 = stringRedisTemplate.opsForValue().get(processedKey);
                if ("1".equals(processed2)) {
                    ack.acknowledge();
                    return;
                }
                throw new IllegalStateException("in-flight processing: " + analysisId);
            }

            // 3) 에러 응답 처리: DB 저장 X, WS+Redis 저장은 하고 processed 찍고 ack
            if (resultNode.path("error").asBoolean(false)) {
                FastApiResponse apiResponse = objectMapper.treeToValue(resultNode, FastApiResponse.class);

                sendProgress(analysisId, 100, "ERROR", "분석 실패");
                sendComplete(analysisId, Map.of(
                        "error", true,
                        "code", "FASTAPI_ERROR",
                        "message", "분석 실패",
                        "analysisId", analysisId
                ));

                analysisResultStore.putResult(analysisId, apiResponse);

                // ✅ 처리완료로 마킹 (에러도 무한 재처리 방지)
                stringRedisTemplate.opsForValue().set(processedKey, "1", 1, TimeUnit.DAYS);

                ack.acknowledge();
                return;
            }

            // 4) 정상 응답: DB 저장(멱등) + WS + Redis + processed + ack
            FastApiResponse apiResponse = objectMapper.treeToValue(resultNode, FastApiResponse.class);

            SiteUser user = null;
            if (userIdRaw != null && !userIdRaw.isBlank()
                    && !"undefined".equalsIgnoreCase(userIdRaw)
                    && !"null".equalsIgnoreCase(userIdRaw)) {
                user = userRepository.findByUsername(userIdRaw);
            }

            // ✅ (user, url) 기준 DB 중복 방지
            String url = apiResponse.url();
            boolean alreadySaved = (user != null && url != null && fastApiRepository.existsByUserAndUrl(user, url));

            if (alreadySaved) {
                log.info("[done] idempotent skip DB save user={}, url={}", user.getUsername(), url);
            } else {
                fastApiService.saveEntity(user, apiResponse); // 여기 실패하면 catch로 → ack 안 됨 → 재처리
            }

            sendProgress(analysisId, 100, "DONE", "분석 완료");
            sendComplete(analysisId, apiResponse);

            analysisResultStore.putResult(analysisId, apiResponse);

            // ✅ 처리완료 마킹
            stringRedisTemplate.opsForValue().set(processedKey, "1", 1, TimeUnit.DAYS);

            // ✅ 성공했을 때만 커밋
            ack.acknowledge();

        } catch (Exception e) {
            log.error("[done] error analysisId={} msg={}", analysisId, e.getMessage(), e);

            // 실패면 ack 안 함 → 재처리됨
            // (락은 풀어줘야 재처리가 가능)
            if (lockKey != null) {
                stringRedisTemplate.delete(lockKey);
            }
            return;

        } finally {
            // 성공이든 중복이든: 락은 정리 (processed 키가 재중복을 막아줌)
            if (lockKey != null) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }
}