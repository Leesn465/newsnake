package com.mysite.sbb.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * Redis ZSET 기반 Delay Queue 리큐잉 스케줄러
 * <p>
 * - 재시도 시간이 도래한 작업을
 * mail:delay(ZSET) → mail:stream(Stream) 으로 이동
 * - Redis만으로 지연 큐를 구현하기 위한 컴포넌트
 */
@Component
@RequiredArgsConstructor
public class MailDelayRequeue {

    private static final String DELAY_ZSET = "mail:delay";
    private static final String STREAM = "mail:stream";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    public void requeueDueJobs() {
        long now = System.currentTimeMillis();

        Set<String> due = redisTemplate.opsForZSet()
                .rangeByScore(DELAY_ZSET, 0, now, 0, 50);

        if (due == null || due.isEmpty()) {
            return;
        }

        for (String payload : due) {
            try {
                MailJob job = objectMapper.readValue(payload, MailJob.class);

                Map<String, String> fields = new HashMap<>();
                fields.put("jobId", job.jobId());
                fields.put("type", job.type());
                fields.put("email", job.email());
                fields.put("retry", String.valueOf(job.retry()));
                fields.put("createdAt", String.valueOf(job.createdAt()));

                redisTemplate.opsForStream().add(STREAM, fields);
                redisTemplate.opsForZSet().remove(DELAY_ZSET, payload);
            } catch (Exception e) {
                // 파싱 실패 시 제거하지 않고 다음 턴에 재시도 (운영에선 DLQ 고려)
            }
        }
    }
}
