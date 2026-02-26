package com.mysite.sbb.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Redis Stream 기반 메일 비동기 워커
 * <p>
 * 구조:
 * - Redis Stream(mail:stream) → 메일 발송 작업 큐
 * - Consumer Group(mail-group)으로 다중 워커 확장 가능
 * - 처리 중 락(mail:processing:{jobId})으로 중복 발송 방지
 * - 성공 시 sent 마킹(mail:sent:{jobId})으로 idempotency 보장
 * <p>
 * 실패 처리:
 * - 1~5회까지 지수적 backoff로 재시도
 * - 재시도는 ZSET 기반 Delay Queue(mail:delay)에 저장
 * - 5회 초과 시 DLQ(mail:dlq)로 이동
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class MailWorker {

    private static final String STREAM = "mail:stream";
    private static final String GROUP = "mail-group";
    private static final String CONSUMER = "mail-worker-1";
    private static final String DELAY_ZSET = "mail:delay";
    private static final String DLQ_STREAM = "mail:dlq";

    private final RedisTemplate<String, String> redisTemplate;
    private final MailService mailService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        Boolean exists = redisTemplate.hasKey(STREAM);
        if (!exists) {
            Map<String, String> seed = new HashMap<>();
            seed.put("seed", "1");
            redisTemplate.opsForStream().add(STREAM, seed);
        }

        try {
            redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
        } catch (Exception e) {
            // BUSYGROUP 등 이미 존재하는 경우는 무시
        }
    }

    @Scheduled(fixedDelay = 300)
    public void poll() {
        try {
            List<MapRecord<String, Object, Object>> records =
                    redisTemplate.opsForStream().read(
                            Consumer.from(GROUP, CONSUMER),
                            StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
                            StreamOffset.create(STREAM, ReadOffset.lastConsumed())
                    );

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                handle(record);
            }
        } catch (RedisSystemException e) {
            if (e.getCause() != null
                    && e.getCause().getMessage() != null
                    && e.getCause().getMessage().contains("NOGROUP")) {

                try {
                    Boolean exists = redisTemplate.hasKey(STREAM);
                    if (!exists) {
                        Map<String, String> seed = new HashMap<>();
                        seed.put("seed", "1");
                        redisTemplate.opsForStream().add(STREAM, seed);
                    }
                    redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
                } catch (Exception ignored) {
                    // BUSYGROUP 등 무시
                }
                return;
            }
            throw e;
        }
    }

    /**
     * 단일 메일 작업 처리
     * <p>
     * - jobId 기반 중복 방지(sentKey)
     * - processingKey로 분산 락 획득
     * - 성공 시 ACK + sent 마킹
     * - 실패 시 retry / delay / DLQ 처리
     */
    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> v = record.getValue();

        if (!v.containsKey("jobId")
                || !v.containsKey("email")
                || !v.containsKey("type")
                || !v.containsKey("retry")
                || !v.containsKey("createdAt")) {

            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            return;
        }

        String jobId = v.get("jobId").toString();
        String email = v.get("email").toString();
        String type = v.get("type").toString();
        int retry = Integer.parseInt((String) v.get("retry"));
        long createdAt = Long.parseLong((String) v.get("createdAt"));

        String sentKey = "mail:sent:" + jobId;
        String processingKey = "mail:processing:" + jobId;

        if (redisTemplate.hasKey(sentKey)) {
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            return;
        }

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(processingKey, "1", 2, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(locked)) {
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
            return;
        }

        try {
            boolean signup = type.equals(MailType.SIGNUP_VERIFY.name());
            mailService.sendMail(email, signup);

            redisTemplate.opsForValue().set(sentKey, "1", 7, TimeUnit.DAYS);
            redisTemplate.delete(processingKey);
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (Exception e) {
            redisTemplate.delete(processingKey);
            onFailure(jobId, email, type, retry, createdAt, record, e);
        }
    }

    /**
     * 메일 전송 실패 처리
     * <p>
     * - 1~5회: 지수적 backoff로 재시도 큐에 등록
     * - 5회 초과: DLQ(mail:dlq)로 이동하여 운영자 분석 대상
     */
    private void onFailure(
            String jobId,
            String email,
            String type,
            int retry,
            long createdAt,
            MapRecord<String, Object, Object> record,
            Exception e
    ) {
        int nextRetry = retry + 1;

        try {
            if (nextRetry <= 5) {
                long delayMs = backoff(nextRetry);
                long runAt = System.currentTimeMillis() + delayMs;

                MailJob job = new MailJob(jobId, type, email, nextRetry, createdAt);
                String payload = objectMapper.writeValueAsString(job);

                redisTemplate.opsForZSet().add(DELAY_ZSET, payload, runAt);
                redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
                return;
            }

            Map<String, String> dlq = new HashMap<>();
            dlq.put("jobId", jobId);
            dlq.put("type", type);
            dlq.put("email", email);
            dlq.put("retry", String.valueOf(nextRetry));
            dlq.put("error", e.getClass().getSimpleName()
                    + ":" + (e.getMessage() == null ? "" : e.getMessage()));
            dlq.put("failedAt", String.valueOf(System.currentTimeMillis()));

            redisTemplate.opsForStream().add(DLQ_STREAM, dlq);
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        } catch (JsonProcessingException je) {
            Map<String, String> dlq = new HashMap<>();
            dlq.put("jobId", jobId);
            dlq.put("type", type);
            dlq.put("email", email);
            dlq.put("retry", String.valueOf(nextRetry));
            dlq.put("error", "JsonProcessingException:"
                    + (je.getMessage() == null ? "" : je.getMessage()));
            dlq.put("failedAt", String.valueOf(System.currentTimeMillis()));

            redisTemplate.opsForStream().add(DLQ_STREAM, dlq);
            redisTemplate.opsForStream().acknowledge(STREAM, GROUP, record.getId());
        }
    }

    private long backoff(int retry) {
        return switch (retry) {
            case 1 -> 60_000L;      // 1m
            case 2 -> 300_000L;     // 5m
            case 3 -> 900_000L;     // 15m
            case 4 -> 3_600_000L;   // 1h
            default -> 10_800_000L; // 3h
        };
    }
}
