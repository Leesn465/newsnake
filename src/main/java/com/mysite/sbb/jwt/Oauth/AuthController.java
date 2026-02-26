package com.mysite.sbb.jwt.Oauth;

import com.mysite.sbb.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OAuth2 로그인 이후, 프론트엔드가 전달한 "인증 코드(code)"를
 * 실제 JWT Access Token으로 교환해주는 컨트롤러
 * <p>
 * 흐름:
 * 1. OAuth2 로그인 성공 (Spring Security)
 * 2. OAuth2LoginSuccessHandler가 1회용 code 발급
 * 3. 프론트엔드는 /api/auth/exchange?code=... 로 요청
 * 4. 서버는 code → username 조회
 * 5. username → JWT Access Token 발급
 * <p>
 * 이 방식은 OAuth2 Authorization Code Flow와 동일한 보안 구조이며,
 * JWT를 URL에 직접 노출하지 않기 위한 설계이다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthCodeStore authCodeStore;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * OAuth2 인증 코드(code)를 JWT Access Token으로 교환
     *
     * @param code OAuth2 로그인 성공 후 발급된 1회용 인증 코드
     * @return JWT Access Token + username
     */
    @PostMapping("/exchange")
    public ResponseEntity<?> exchange(@RequestParam String code) {

        log.info("OAuth2 code → JWT 교환 요청: {}", code);

        // 1 code → username 조회 (1회용)
        String username = authCodeStore.consume(code);

        // 이미 사용되었거나 만료된 code인 경우
        if (username == null) {
            log.warn("⚠️ OAuth2 code already consumed or expired: {}", code);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_or_expired_code"));
        }

        // 2 username → JWT Access Token 생성
        String accessToken = jwtTokenProvider.createAccessToken(username);

        log.info("JWT 발급 완료: user={}", username);

        // 3 프론트엔드로 JWT 반환
        return ResponseEntity.ok(
                Map.of(
                        "accessToken", accessToken,
                        "username", username
                )
        );
    }
}
