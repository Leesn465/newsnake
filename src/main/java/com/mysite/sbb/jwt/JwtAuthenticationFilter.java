package com.mysite.sbb.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.user.SiteUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;


@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
        log.info("로그인 시도: {}", request.getRequestURI());
        try {
            // form-login이 아닌 JSON 기반 로그인(API 서버 구조)을 지원하기 위해 Request Body에서 직접 파싱
            SiteUser creds = new ObjectMapper().readValue(request.getInputStream(), SiteUser.class);
            log.info("입력받은 ID: {}", creds.getUsername());
            // Spring Security 표준 인증 토큰을 생성해 AuthenticationManager에게 위임
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(creds.getUsername(), creds.getPassword());
            return authenticationManager.authenticate(authToken);
        } catch (IOException e) {
            log.error("로그인 요청 파싱 실패", e);
            throw new AuthenticationServiceException("로그인 요청 파싱 실패", e);
        }
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest req, HttpServletResponse res,
                                            FilterChain chain, Authentication auth) throws IOException {
        log.info("로그인 성공: {}", auth.getName());
        String username = auth.getName();
        // 로그인 성공 시 서버 세션 대신 JWT Access Token 발급 (Stateless 인증)
        String accessToken = jwtTokenProvider.createAccessToken(username);

        res.setContentType("application/json;charset=UTF-8");
        // 표준 Authorization: Bearer 방식으로 프론트 및 API Gateway와 연동
        res.setHeader("Authorization", "Bearer " + accessToken);
        // SPA(React)에서 토큰을 저장해 이후 API 요청 시 Authorization 헤더로 사용
        res.getWriter().write("{\"accessToken\":\"" + accessToken + "\",\"username\":\"" + username + "\"}");
        res.getWriter().flush();
    }


    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              AuthenticationException failed) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String message = "로그인에 실패했습니다";

        // 인증 실패 원인을 구분해 사용자 UX 개선
        if (failed instanceof UsernameNotFoundException) {
            message = "아이디가 없습니다";
        } else if (failed instanceof BadCredentialsException) {
            message = "비밀번호가 다릅니다";
        }

        // 에러도 JSON으로 통일해 프론트엔드에서 일관된 처리 가능
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "AUTH_FAILED",
                "message", message
        ));
    }

}