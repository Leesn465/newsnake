package com.mysite.sbb.fastapi.Kafka;

import com.mysite.sbb.fastapi.FastApiEntity;
import com.mysite.sbb.fastapi.FastApiRepository;
import com.mysite.sbb.fastapi.FastApiResponse;
import com.mysite.sbb.fastapi.FastApiService;
import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeAsyncResponseDTO;
import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeRequestEvent;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import com.mysite.sbb.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyzeFetchAndSave {
    private final FastApiRepository fastApiRepository;
    private final FastApiService fastApiService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;
    private final AnalyzeKafkaProducer kafkaProducer;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final UserRepository userRepository;


    @Transactional
    public AnalyzeAsyncResponseDTO start(String url, String userId) {
        String analysisId = UUID.randomUUID().toString();
        log.info("[START] analysisId={}, url={}, userId={}", analysisId, url, userId);

        SiteUser user = (userId != null && !userId.isBlank())
                ? userRepository.findByUsername(userId)
                : null;

        FastApiEntity existing = fastApiRepository.findFirstByUrl(url);

        // 캐시 히트: WS 발사하지 말고 HTTP로 결과 반환
        if (existing != null) {
            FastApiResponse resp = existing.toResponse();

            if (user != null && !fastApiRepository.existsByUserAndUrl(user, url)) {
                // 유저-URL 연결만 추가 저장
                fastApiService.saveEntity(user, resp);
                log.info("[Cache Hit] 새 유저 기록 저장: userId={}, url={}", userId, url);
            }

            return new AnalyzeAsyncResponseDTO(analysisId, true, resp);
        }

        // 캐시 미스: Kafka로 분석 요청 (WS는 KafkaListener가 쏴줌)
        publishProgress(analysisId, 0, "START", "분석 요청 전송 중.");
        applicationEventPublisher.publishEvent(new AnalyzeRequestEvent(analysisId, url, userId));

        return new AnalyzeAsyncResponseDTO(analysisId, false, null);
    }

    private void publishProgress(String analysisId, int percent, String stage, String message) {
        messagingTemplate.convertAndSend(
                "/topic/analyze-progress/" + analysisId,
                Map.of("analysisId", analysisId, "percent", percent, "stage", stage, "message", message)
        );
    }

    private void publishComplete(String analysisId, FastApiResponse resp, String message) {
        publishProgress(analysisId, 100, "DONE", message);
        messagingTemplate.convertAndSend("/topic/analyze-complete/" + analysisId, resp);
    }
}

