package com.mysite.sbb.jwt;

import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import com.mysite.sbb.user.SiteUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthorizationFilterTest {

    AuthenticationManager authenticationManager;
    UserDetailsService userDetailsService;
    JwtTokenProvider jwtTokenProvider;
    JwtAuthorizationFilter filter;
    FilterChain filterChain;
    MockHttpServletRequest request;
    MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        userDetailsService = mock(UserDetailsService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);

        filter = new JwtAuthorizationFilter(authenticationManager, userDetailsService, jwtTokenProvider);

        filterChain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1) Authorization 헤더가 없으면 체인만 실행되고 인증은 없음")
    void noHeader_passThrough() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("2) Bearer prefix 없는 경우도 패스")
    void noBearerPrefix_passThrough() throws Exception {
        request.addHeader("Authorization", "INVALIDTOKEN");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("3) JWT 유효하지 않으면 401")
    void invalidJwt_returns401() throws Exception {
        request.addHeader("Authorization", "Bearer INVALID");

        when(jwtTokenProvider.validateToken("INVALID")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("4) 유효한 JWT → SecurityContext에 Authentication 저장됨")
    void validJwt_authenticated() throws Exception {

        request.addHeader("Authorization", "Bearer VALID");

        when(jwtTokenProvider.validateToken("VALID")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("VALID")).thenReturn("testUser");

        SiteUser user = new SiteUser();
        user.setUsername("testUser");
        user.setPassword("ENCODED");

        PrincipalDetails principal = new PrincipalDetails(user, Map.of());

        when(userDetailsService.loadUserByUsername("testUser"))
                .thenReturn(principal);

        filter.doFilterInternal(request, response, filterChain);

        // 체인은 정상 실행
        verify(filterChain, times(1)).doFilter(request, response);

        // SecurityContext에 인증 객체 저장됨
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("testUser");
    }

    @Test
    @DisplayName("5) 내부 예외 발생 시 500 반환")
    void exceptionInsideFilter_returns500() throws Exception {

        request.addHeader("Authorization", "Bearer VALID");

        // validateToken 중 폭파시키기
        when(jwtTokenProvider.validateToken("VALID")).thenThrow(new RuntimeException("Boom"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(500);
        verify(filterChain, never()).doFilter(request, response);
    }
}
