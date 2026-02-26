package com.mysite.sbb.jwt.Oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * OAuth2 로그인 성공 시 호출되는 핸들러
 * <p>
 * 역할:
 * 1) OAuth2 인증이 끝난 사용자의 정보를 가져온다
 * 2) 실제 JWT를 바로 노출하지 않고, 임시 인증 코드(Auth Code)를 발급한다
 * 3) 프론트엔드로 code를 포함한 redirect를 수행한다
 * <p>
 * 목적:
 * - OAuth2 Provider(Google, Kakao 등) → Spring Security → React
 * 이 흐름을 안전하게 연결하기 위한 브릿지
 * - 토큰을 URL에 직접 노출하지 않고, 서버 기반 인증 흐름을 유지하기 위함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    /**
     * OAuth 로그인 후 발급할 임시 인증 코드 저장소
     * (code → username 매핑, Redis 또는 메모리 캐시로 구현 가능)
     */
    private final AuthCodeStore authCodeStore;

    /**
     * OAuth2 인증이 성공했을 때 Spring Security가 자동 호출하는 메서드
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        log.info("🔥 OAuth2 로그인 성공");

        // Spring Security가 만든 인증 객체에서 사용자 정보 꺼내기
        PrincipalDetails principal = (PrincipalDetails) authentication.getPrincipal();
        log.info("✅ 로그인 사용자: {}", principal.getUsername());

        /**
         * 실제 JWT를 바로 발급하지 않고,
         * 1회용 임시 코드(Auth Code)를 발급한다.
         *
         * 이유:
         * - 프론트 URL에 JWT가 노출되는 것을 방지
         * - OAuth2 Authorization Code Flow와 유사한 구조 유지
         */
        String code = authCodeStore.saveAndGetCode(principal.getUsername());
        log.info("✅ 임시 인증 코드 발급: {}", code);

        /**
         * React 프론트엔드로 code를 포함하여 redirect
         * 프론트는 이 code를 다시 백엔드에 보내
         * → 실제 JWT를 교환받는다
         */
        String redirectUrl = "http://localhost:3000/oauth2/redirect?code=" + code;
        log.info("🚀 Redirect: {}", redirectUrl);

        response.sendRedirect(redirectUrl);
    }
}