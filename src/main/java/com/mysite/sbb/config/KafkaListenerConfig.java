package com.mysite.sbb.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer 세부 설정 클래스
 * <p>
 * 목적:
 * - analysis-progress 와 analysis-done 토픽을
 * 서로 다른 소비 전략으로 처리하기 위함
 * <p>
 * 특징:
 * - AUTO_OFFSET_RESET 전략을 토픽별로 다르게 설정
 * - 수동 Ack(MANUAL_IMMEDIATE) 사용
 * <p>
 * 왜 분리했는가?
 * progress 이벤트와 done 이벤트는
 * 신뢰성과 처리 전략이 다르기 때문
 */
@Configuration
public class KafkaListenerConfig {

    /**
     * analysis-done 전용 ConsumerFactory
     * <p>
     * 설정:
     * - AUTO_OFFSET_RESET = earliest
     * → 혹시 서버 재시작 시 과거 완료 이벤트도 다시 읽도록 설정
     * <p>
     * - ENABLE_AUTO_COMMIT = false
     * → 수동 ack 사용 (정확히 성공 시에만 commit)
     * <p>
     * done 이벤트는 반드시 처리되어야 하므로
     * 보수적인 전략을 사용
     */
    @Bean
    public ConsumerFactory<String, String> doneConsumerFactory(KafkaProperties props) {
        Map<String, Object> config = new HashMap<>(props.buildConsumerProperties());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); //  done은 과거부터
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * analysis-progress 전용 ConsumerFactory
     * <p>
     * 설정:
     * - AUTO_OFFSET_RESET = latest
     * → 과거 진행률은 의미 없음 (실시간만 중요)
     * <p>
     * - ENABLE_AUTO_COMMIT = false
     * <p>
     * progress 이벤트는 유실되어도 큰 문제 없으므로
     * 최신 위주 전략 사용
     */
    @Bean
    public ConsumerFactory<String, String> progressConsumerFactory(KafkaProperties props) {
        Map<String, Object> config = new HashMap<>(props.buildConsumerProperties());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"); //  progress는 최신부터
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * analysis-done ListenerContainerFactory
     * <p>
     * AckMode = MANUAL_IMMEDIATE
     * <p>
     * 의미:
     * - 메시지 처리 성공 시에만 수동으로 acknowledge()
     * - 실패하면 offset commit 안 됨 → 재처리
     * <p>
     * 즉, at-least-once 보장 전략
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> doneKafkaListenerContainerFactory(
            ConsumerFactory<String, String> doneConsumerFactory) {


        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(doneConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE); // 수동 ack 쓰니까
        return factory;
    }

    /**
     * analysis-progress ListenerContainerFactory
     * <p>
     * done과 동일하게 수동 ack 사용
     * (progress도 파싱 실패 시 commit 제어 가능)
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> progressKafkaListenerContainerFactory(
            ConsumerFactory<String, String> progressConsumerFactory) {


        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(progressConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }


}