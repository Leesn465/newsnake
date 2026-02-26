package com.mysite.sbb.jwt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.mysite.sbb.user.Role.Role;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    private UserRepository userRepository;
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        jwtTokenProvider = new JwtTokenProvider(userRepository);

        // 공통 mock 설정: roles 포함
        SiteUser mockUser = new SiteUser();
        mockUser.setUsername("testUser");
        mockUser.setPassword("1234");
        Role role = new Role();
        role.setRoleName("ROLE_USER");

        mockUser.setRoles(Set.of(role));


        when(userRepository.findByUsername("testUser"))
                .thenReturn(mockUser);
    }

    @Test
    @DisplayName("AccessToken 생성 성공")
    void createAccessToken_success() {
        String token = jwtTokenProvider.createAccessToken("testUser");

        assertThat(token).isNotNull();
        assertThat(token.length()).isGreaterThan(10);

        // JWT 내부 claim 검증
        String username = JWT.require(Algorithm.HMAC512(JwtConstants.SECRET_KEY))
                .withIssuer(JwtConstants.ISSUER)
                .build()
                .verify(token)
                .getClaim("username")
                .asString();

        assertThat(username).isEqualTo("testUser");
    }

    @Test
    @DisplayName("validateToken() → 유효한 토큰 true")
    void validateToken_validToken() {
        String token = jwtTokenProvider.createAccessToken("testUser");

        boolean result = jwtTokenProvider.validateToken(token);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("validateToken() → 잘못된 서명 false")
    void validateToken_invalidSignature() {
        // 비정상 토큰
        String fakeToken = JWT.create()
                .withSubject("testUser")
                .sign(Algorithm.HMAC512("WRONG_KEY"));

        boolean result = jwtTokenProvider.validateToken(fakeToken);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("extractUsername() → username 추출 성공")
    void extractUsername_success() {
        String token = jwtTokenProvider.createAccessToken("testUser");

        String username = jwtTokenProvider.extractUsername(token);

        assertThat(username).isEqualTo("testUser");
    }

    @Test
    @DisplayName("createCustomToken() → 만료 시간 반영")
    void createCustomToken_expireWorks() {
        long expireMillis = 2000L; // 2초
        String token = jwtTokenProvider.createCustomToken("testUser", expireMillis);

        var decoded = JWT.require(Algorithm.HMAC512(JwtConstants.SECRET_KEY))
                .withIssuer(JwtConstants.ISSUER)
                .build()
                .verify(token);

        assertThat(decoded.getClaim("username").asString()).isEqualTo("testUser");
        assertThat(decoded.getExpiresAt()).isNotNull();
    }
}
