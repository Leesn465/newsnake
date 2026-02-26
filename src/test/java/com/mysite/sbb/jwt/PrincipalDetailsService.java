package com.mysite.sbb.jwt;

import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import com.mysite.sbb.jwt.Oauth.PrincipalDetailsService;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrincipalDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private PrincipalDetailsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PrincipalDetailsService(userRepository);
    }

    @Test
    @DisplayName("정상 로그인: username 으로 사용자 조회 성공")
    void loadUserByUsername_success() {
        // given
        SiteUser user = SiteUser.builder()
                .username("minseung")
                .password("encoded_pw")
                .build();

        when(userRepository.findByUsername("minseung")).thenReturn(user);

        // when
        UserDetails result = service.loadUserByUsername("minseung");

        // then
        assertThat(result).isInstanceOf(PrincipalDetails.class);
        assertThat(result.getUsername()).isEqualTo("minseung");
        verify(userRepository).findByUsername("minseung");
    }

    @Test
    @DisplayName("사용자 없음 → UsernameNotFoundException 발생")
    void loadUserByUsername_failure() {
        // given
        when(userRepository.findByUsername("unknown")).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> service.loadUserByUsername("unknown"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("unknown");

        verify(userRepository).findByUsername("unknown");
    }
}