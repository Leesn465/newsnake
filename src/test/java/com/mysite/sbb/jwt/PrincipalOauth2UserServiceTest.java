package com.mysite.sbb.jwt;

import com.mysite.sbb.jwt.Oauth.PrincipalOauth2UserService;
import com.mysite.sbb.user.Role.Role;
import com.mysite.sbb.user.Role.RoleRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PrincipalOauth2UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OAuth2User mockOAuth2User;

    private PrincipalOauth2UserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Spy로 생성
        service = Mockito.spy(new PrincipalOauth2UserService(
                userRepository, roleRepository, passwordEncoder
        ));
    }


    private OAuth2UserRequest createOAuthRequest(String provider, Map<String, Object> attrs) {
        ClientRegistration reg = ClientRegistration.withRegistrationId(provider)
                .clientId("testClient")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("email", "profile")
                .authorizationUri("https://test.com/auth")
                .tokenUri("https://test.com/token")
                .redirectUri("https://test.com/redirect")
                .userInfoUri("https://test.com/me")
                .userNameAttributeName("sub")
                .build();

        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "fake-token", null, null
        );

        when(mockOAuth2User.getAttributes()).thenReturn(attrs);

        return new OAuth2UserRequest(reg, token);
    }

    @Test
    @DisplayName("신규 OAuth 사용자 → 자동 회원가입")
    void newOAuthUserSignup() {
        Map<String, Object> attrs = Map.of(
                "email", "test@gmail.com",
                "name", "민승",
                "sub", "id"
        );

        OAuth2UserRequest req = createOAuthRequest("google", attrs);

        // super.loadUser를 감싸는 loadOAuth2User()만 Mock
        doReturn(mockOAuth2User)
                .when(service).loadOAuth2User(any());

        Role role = new Role();
        role.setRoleName("ROLE_USER");

        when(userRepository.findByEmail("test@gmail.com")).thenReturn(null);
        when(roleRepository.findByRoleName("ROLE_USER")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");

        service.loadUser(req);

        verify(userRepository).save(any(SiteUser.class));
    }

    @Test
    @DisplayName("기존 OAuth 사용자 로그인 → save 호출 X")
    void existingOAuthUserLogin() {
        Map<String, Object> attrs = Map.of(
                "email", "exist@gmail.com",
                "name", "User",
                "sub", "id"
        );

        OAuth2UserRequest req = createOAuthRequest("google", attrs);

        doReturn(mockOAuth2User)
                .when(service).loadOAuth2User(any());

        SiteUser exist = SiteUser.builder()
                .email("exist@gmail.com")
                .username("exist_google")
                .build();

        when(userRepository.findByEmail("exist@gmail.com")).thenReturn(exist);

        service.loadUser(req);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("지원하지 않는 provider → 예외 발생")
    void invalidProvider() {
        Map<String, Object> attrs = Map.of(
                "email", "x@test.com",
                "name", "User",
                "sub", "id"
        );

        OAuth2UserRequest req = createOAuthRequest("unknown", attrs);

        // loadOAuth2User()는 계속 Mock된 상태여야 함 → super.loadUser() 호출 방지
        doReturn(mockOAuth2User)
                .when(service)
                .loadOAuth2User(any());

        assertThatThrownBy(() -> service.loadUser(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 provider");
    }
}