package com.mysite.sbb.fastapi.Kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeEventDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 뉴스 분석 요청 이벤트를 Kafka로 발행(Producer)하는 서비스.
 * <p>
 * 역할:
 * - 분석 요청을 DB 처리와 분리
 * - 비동기 이벤트 기반으로 FastAPI(또는 외부 분석 서버)와 통신
 * - 트래픽 급증 시에도 Kafka가 버퍼 역할을 수행
 * <p>
 * 구조:
 * Spring API → Kafka Topic → Consumer(FastAPI or 분석 서버)
 */
@Service
@RequiredArgsConstructor
public class AnalyzeKafkaProducer {

    /**
     * Kafka 메시지 발행을 위한 템플릿.
     * <Key, Value> 구조로 메시지를 전송한다.
     */
    private final KafkaTemplate<String, String> kafkaTemplate;
    /**
     * DTO → JSON 직렬화를 위한 ObjectMapper.
     * Kafka는 기본적으로 byte[] 또는 String 전송이므로
     * 이벤트 객체를 JSON으로 변환해 전송한다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();


    /**
     * 분석 요청을 보낼 Kafka 토픽 이름.
     * application.yml에서 설정 가능하며,
     * 기본값은 "news-analyze"
     */
    @Value("${kafka.topic.request:news-analyze}")
    private String requestTopic;

    /**
     * 뉴스 분석 요청 이벤트 발행 메서드.
     *
     * @param analysisId 분석 작업 고유 ID (Key로 사용 → 파티션 정렬 보장)
     * @param url        분석할 뉴스 URL
     * @param userId     요청 사용자 ID
     *                   <p>
     *                   동작:
     *                   1) DTO 생성
     *                   2) JSON 직렬화
     *                   3) Kafka Topic에 publish
     *                   <p>
     *                   key = analysisId 를 사용하는 이유:
     *                   - 동일 analysisId는 같은 파티션으로 전송
     *                   - 순서(ordering) 보장 가능
     */
    public void publishAnalyzeRequest(String analysisId, String url, String userId) {
        try {
            // 분석 요청 이벤트 객체 생성
            AnalyzeEventDTO event = new AnalyzeEventDTO(analysisId, url, userId);
            String json = objectMapper.writeValueAsString(event);


            /**
             * Kafka 메시지 전송
             * <p>
             * send(topic, key, value)
             * - topic : 발행 대상 토픽
             * - key   : 파티션 분배 기준
             * - value : 실제 전송 데이터(JSON)
             */
            kafkaTemplate.send(requestTopic, analysisId, json);

        } catch (Exception e) {
            throw new RuntimeException("Kafka publish 실패: " + e.getMessage(), e);
        }
    }
}
