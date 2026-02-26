package com.mysite.sbb.fastapi;


import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;

@Profile("test")
@TestConfiguration
public class TestSchedulingConfig {
    // 테스트에서는 자동 스케줄링 비활성화
    // @Scheduled는 SpringBootTest에서도 실행되므로 이를 방지
}