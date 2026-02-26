package com.mysite.sbb.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysite.sbb.user.SiteUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    AuthenticationManager authenticationManager;
    JwtTokenProvider jwtTokenProvider;
    JwtAuthenticationFilter filter;

    MockHttpServletRequest request;
    MockHttpServletResponse response;
    FilterChain chain;

    @BeforeEach
    void setup() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        filter = new JwtAuthenticationFilter(authenticationManager, jwtTokenProvider);

        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("1) attemptAuthentication - JSON 파싱 성공 → authenticate() 호출됨")
    void attemptAuthentication_success() throws Exception {

        SiteUser loginUser = new SiteUser();
        loginUser.setUsername("testUser");
        loginUser.setPassword("1234");

        ObjectMapper om = new ObjectMapper();
        byte[] json = om.writeValueAsBytes(loginUser);

        request.setContent(json);

        Authentication mockAuth = mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(mockAuth);

        Authentication result = filter.attemptAuthentication(request, response);

        assertThat(result).isNotNull();
        verify(authenticationManager, times(1)).authenticate(any());
    }

    @Test
    @DisplayName("2) attemptAuthentication - JSON 파싱 실패 시 AuthenticationServiceException 발생")
    void attemptAuthentication_fail_invalidJson() {

        request.setContent("INVALID_JSON".getBytes());

        assertThatThrownBy(() ->
                filter.attemptAuthentication(request, response)
        ).isInstanceOf(AuthenticationServiceException.class)
                .hasMessageContaining("로그인 요청 파싱 실패");
    }

    @Test
    @DisplayName("3) successfulAuthentication - JWT 생성 + 헤더 + 바디 정상 출력")
    void successfulAuthentication() throws Exception {

        // GIVEN
        String token = "mocked-jwt-token";

        when(jwtTokenProvider.createAccessToken("testUser"))
                .thenReturn(token);

        // Spring Security 기본 UserDetails 사용
        UserDetails principal = User.builder()
                .username("testUser")
                .password("ENCODED")
                .roles("USER")
                .build();

        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()
        );

        // WHEN
        filter.successfulAuthentication(request, response, chain, auth);

        // THEN
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Authorization")).isEqualTo("Bearer " + token);

        String body = response.getContentAsString();
        assertThat(body).contains("\"accessToken\":\"" + token + "\"");
        assertThat(body).contains("\"username\":\"testUser\"");
    }
}
