package com.mysite.sbb.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

// Spring Security 필터 체인에 JWT 인증 단계를 삽입하기 위한 커스텀 인가 필터
@Slf4j
public class JwtAuthorizationFilter extends BasicAuthenticationFilter {

    private final UserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthorizationFilter(AuthenticationManager authenticationManager,
                                  UserDetailsService userDetailsService,
                                  JwtTokenProvider jwtTokenProvider) {
        super(authenticationManager);
        this.userDetailsService = userDetailsService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        try {
            // Authorization 헤더가 없는 요청은 익명 요청으로 처리 (로그인, 공개 API 등)
            String header = req.getHeader(JwtConstants.AUTH_HEADER);
            if (header == null || !header.startsWith(JwtConstants.AUTH_PREFIX)) {
                chain.doFilter(req, res);
                return;
            }

            // "Bearer " 접두어를 제거하고 실제 JWT 값만 추출
            String token = header.substring(JwtConstants.AUTH_PREFIX.length());

            // 만료·위조·서명 오류가 있는 토큰은 즉시 차단
            if (!jwtTokenProvider.validateToken(token)) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            String username = jwtTokenProvider.extractUsername(token);
            // JWT의 username을 기반으로 현재 사용자 권한(Role)을 다시 로드
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            Authentication auth =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            // 이후 컨트롤러에서 @AuthenticationPrincipal, hasRole() 사용 가능하게 컨텍스트에 등록
            SecurityContextHolder.getContext().setAuthentication(auth);

            chain.doFilter(req, res);
        } catch (Exception e) {
            log.error("JWT Authorization 처리 중 예외", e);
            try {
                res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception ignore) {
            }
        }
    }
}