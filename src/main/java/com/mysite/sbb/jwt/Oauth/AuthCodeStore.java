package com.mysite.sbb.jwt.Oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 로그인 과정에서 사용하는 임시 인증 코드(Auth Code) 저장소
 * <p>
 * 역할:
 * - OAuth2 로그인 성공 시
 * username → 1회용 code 로 변환해서 저장
 * - 프론트엔드가 code를 다시 서버로 보내면
 * code → username 으로 교환하고 즉시 폐기
 * <p>
 * 목적:
 * - JWT를 URL에 직접 노출하지 않기 위함
 * - OAuth2 Authorization Code Flow와 동일한 보안 구조를 유지하기 위함
 * <p>
 * 저장소:
 * - Redis가 존재하면 Redis 사용 (운영 환경)
 * - Redis가 없으면 메모리(Map) 사용 (로컬 개발 환경)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthCodeStore {

    // Redis에 저장할 key prefix
    private static final String KEY_PREFIX = "OAUTH2_CODE:";

    // 인증 코드 유효 시간 (5분)
    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * Redis Template
     * - 운영 환경에서는 Redis 사용
     * - 로컬 개발 시 Redis 미설치 환경에서는 null일 수 있음
     */
    private final StringRedisTemplate redis;

    /**
     * Redis가 없을 때 사용하는 in-memory fallback 저장소
     * (개발 환경용)
     */
    private final ConcurrentHashMap<String, String> memory = new ConcurrentHashMap<>();


    /**
     * username에 대한 1회용 OAuth2 인증 코드 발급
     *
     * @param username OAuth 로그인한 사용자 ID
     * @return 1회용 인증 코드 (UUID)
     */
    public String saveAndGetCode(String username) {
        String code = UUID.randomUUID().toString();

        // Redis가 있으면 Redis에 저장 (운영 환경)
        if (redis != null) {
            redis.opsForValue().set(KEY_PREFIX + code, username, TTL);
        }
        // Redis가 없으면 메모리에 저장 (개발 환경)
        else {
            memory.put(code, username);
        }

        return code;
    }


    /**
     * 프론트엔드에서 전달된 code를 실제 사용자로 교환
     * 사용 후 즉시 삭제하여 재사용을 방지한다 (1회용)
     *
     * @param code 프론트에서 전달된 OAuth 인증 코드
     * @return 해당 code에 매핑된 username (없으면 null)
     */
    public String consume(String code) {
        String key = KEY_PREFIX + code;

        // Redis 사용 시
        if (redis != null) {
            String username = redis.opsForValue().get(key);

            if (username == null) {
                log.info("consume() failed: code not found or expired -> {}", code);
            } else {
                log.info("consume() success for user -> {}", username);
                // 1회용 코드이므로 사용 후 즉시 삭제
                redis.delete(key);
            }
            return username;
        }
        // 메모리 사용 시
        else {
            String username = memory.remove(code);

            if (username == null)
                log.info("memory consume() failed -> {}", code);
            else
                log.info("memory consume() success -> {}", username);

            return username;
        }
    }
}