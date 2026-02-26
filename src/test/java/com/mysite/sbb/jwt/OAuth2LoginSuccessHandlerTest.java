package com.mysite.sbb.jwt;

import com.mysite.sbb.jwt.Oauth.AuthCodeStore;
import com.mysite.sbb.jwt.Oauth.OAuth2LoginSuccessHandler;
import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OAuth2LoginSuccessHandlerTest {

    private AuthCodeStore authCodeStore;
    private OAuth2LoginSuccessHandler successHandler;

    @BeforeEach
    void setup() {
        authCodeStore = mock(AuthCodeStore.class);
        successHandler = new OAuth2LoginSuccessHandler(authCodeStore);
    }

    @Test
    @DisplayName("OAuth2 로그인 성공 시 → 임시 코드 발급 + /oauth2/redirect 로 리다이렉트")
    void onAuthenticationSuccess_redirectsProperly() throws Exception {

        // 1) Request/Response mock
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        // 2) PrincipalDetails mock
        PrincipalDetails principal = mock(PrincipalDetails.class);
        when(principal.getUsername()).thenReturn("testUser");

        // 3) Authentication mock
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        // 4) AuthCodeStore mock behavior
        when(authCodeStore.saveAndGetCode("testUser")).thenReturn("ABCDE12345");

        // 5) 실행
        successHandler.onAuthenticationSuccess(request, response, authentication);

        // 6) 리다이렉트 URL 캡처
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(response, times(1)).sendRedirect(urlCaptor.capture());

        String redirectUrl = urlCaptor.getValue();

        // 7) 검증
        assertThat(redirectUrl)
                .isEqualTo("https://www.newsnake.site/oauth2/redirect?code=ABCDE12345");

        // AuthCodeStore는 정상 호출되었는지?
        verify(authCodeStore, times(1)).saveAndGetCode("testUser");
    }
}
