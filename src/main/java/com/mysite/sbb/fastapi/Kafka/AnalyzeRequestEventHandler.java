package com.mysite.sbb.fastapi.Kafka;

import com.mysite.sbb.fastapi.Kafka.DTO.AnalyzeRequestEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분석 요청 이벤트 핸들러
 * 목적:
 * - DB 트랜잭션이 성공적으로 커밋된 이후에
 * Kafka로 분석 요청을 발행하기 위한 클래스
 * 왜 필요한가?
 * - 만약 DB 저장 전에 Kafka를 먼저 publish하면
 * DB 롤백 시 데이터 정합성 문제가 발생할 수 있음
 * 해결 방법:
 * - @TransactionalEventListener(phase = AFTER_COMMIT)
 * → 트랜잭션이 "성공적으로 커밋된 이후"에만 Kafka 발행
 * 즉, 데이터 정합성을 보장하는 이벤트 기반 설계
 */
@Component
@RequiredArgsConstructor
public class AnalyzeRequestEventHandler {
    private final AnalyzeKafkaProducer kafkaProducer;

    /**
     * 트랜잭션 커밋 이후 실행됨
     * 흐름:
     * 1. 서비스 계층에서 AnalyzeRequestEvent 발행
     * 2. 트랜잭션 성공적으로 commit
     * 3. 그 후 Kafka로 메시지 publish
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(AnalyzeRequestEvent event) {
        kafkaProducer.publishAnalyzeRequest(event.analysisId(), event.url(), event.userId());
    }
}