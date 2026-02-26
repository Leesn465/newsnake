package com.mysite.sbb.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.UUID;

/**
 * JWT 발급 및 검증을 전담하는 컴포넌트
 * <p>
 * 이 클래스는 "Stateless 인증"을 구현하기 위한 핵심 컴포넌트로,
 * 서버가 세션을 저장하지 않고도 사용자를 인증할 수 있도록
 * JWT(JSON Web Token)를 생성·검증한다.
 * <p>
 * 주요 역할:
 * - Access Token 생성
 * - 토큰 위조/만료 검증
 * - 토큰에서 사용자 정보 추출
 * - 다양한 목적(이메일 인증, OAuth 임시 토큰 등)의 커스텀 토큰 발급
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final UserRepository userRepository;


    /**
     * 로그인 또는 OAuth2 교환 성공 시,
     * 실제 API 호출에 사용될 Access Token을 발급한다.
     * <p>
     * JWT 안에 roles 정보를 포함시켜,
     * 매 요청마다 DB를 조회하지 않고도 권한 체크가 가능하도록 설계했다.
     */
    public String createAccessToken(String username) {
        SiteUser user = userRepository.findByUsername(username);
        // JWT에 Role을 포함시켜 DB 조회 없이도 권한 체크 가능하게 설계
        String[] roles = user.getRoles().stream()
                .map(r -> r.getRoleName()).toArray(String[]::new);

        long now = System.currentTimeMillis();
        return JWT.create()
                .withSubject(username)
                .withIssuer(JwtConstants.ISSUER)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + JwtConstants.ACCESS_TOKEN_EXPIRATION_MILLIS))
                // 토큰 식별자(jti)를 부여해 추후 블랙리스트 또는 로그 추적 가능하게 설계
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("username", username)
                .withArrayClaim("roles", roles)
                .sign(Algorithm.HMAC512(JwtConstants.SECRET_KEY));
    }


    /**
     * 전달받은 JWT가 유효한지 검증한다.
     * <p>
     * 만료, 위조, 서명 오류를 구분하여 로그로 남겨
     * 운영 환경에서 보안 추적이 가능하도록 설계했다.
     */
    public boolean validateToken(String token) {
        try {
            JWT.require(Algorithm.HMAC512(JwtConstants.SECRET_KEY))
                    .withIssuer(JwtConstants.ISSUER)
                    .build()
                    .verify(token);
            return true;
        } catch (TokenExpiredException e) {
            log.warn("JWT 만료: {}", e.getMessage());
        } catch (JWTVerificationException e) {
            log.error("JWT 검증 실패: {}", e.getMessage());
        } catch (Exception e) {
            log.error("JWT 검증 중 예외", e);
        }
        return false;
    }

    /**
     * JWT 내부에서 username 클레임을 추출한다.
     * <p>
     * 이 값은 Spring Security의 Authentication 생성에 사용된다.
     */
    public String extractUsername(String token) {
        return JWT.require(Algorithm.HMAC512(JwtConstants.SECRET_KEY))
                .withIssuer(JwtConstants.ISSUER)
                .build()
                .verify(token)
                .getClaim("username")
                .asString();
    }

    /**
     * AccessToken 외에도,
     * 이메일 인증, OAuth 임시 토큰, 비밀번호 재설정 등
     * 단기용 토큰 발급을 위해 범용 JWT 생성 메서드로 분리
     */
    public String createCustomToken(String username, long expireMillis) {
        long now = System.currentTimeMillis();

        return JWT.create()
                .withSubject(username)
                .withIssuer(JwtConstants.ISSUER)
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + expireMillis))
                .withClaim("username", username)
                .sign(Algorithm.HMAC512(JwtConstants.SECRET_KEY));
    }

}