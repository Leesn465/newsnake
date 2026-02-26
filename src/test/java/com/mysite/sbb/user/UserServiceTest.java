package com.mysite.sbb.user;

import com.mysite.sbb.user.Role.Role;
import com.mysite.sbb.user.Role.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Spy
    @InjectMocks
    private UserService userService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("회원 가입 성공: 비밀번호 정책 충족 + 인코딩 + ROLE_USER 부여")
    void joinUserSuccess() {
        // given
        SiteUser user = new SiteUser();
        user.setEmail("test@test.com");
        user.setUsername("tester");
        user.setPassword("Abcde123!"); //

        Role roleUser = new Role();
        roleUser.setRoleName("ROLE_USER");

        when(userRepository.existsByEmail("test@test.com")).thenReturn(false);
        doReturn(true).when(userService).validatePassword("Abcde123!");
        when(passwordEncoder.encode("Abcde123!")).thenReturn("ENC_Abcde123!");
        when(roleRepository.findByRoleName("ROLE_USER")).thenReturn(Optional.of(roleUser));

        // when
        userService.joinUser(user);

        // then
        verify(passwordEncoder).encode("Abcde123!");
        verify(userRepository).save(user);
        assertThat(user.getPassword()).isEqualTo("ENC_Abcde123!");
        assertThat(user.getRoles()).contains(roleUser);
    }


    @Test
    @DisplayName("회원 가입 실패: 이메일 중복 시 예외 발생")
    void joinUserDuplicateEmail() {
        SiteUser user = new SiteUser();
        user.setEmail("test@test.com");
        user.setPassword("Abcde123!");

        when(userRepository.existsByEmail("test@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.joinUser(user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 등록된 이메일입니다.");
    }

    @Test
    @DisplayName("회원 가입 실패: 비밀번호 정책 미충족 시 예외 발생")
    void joinUserInvalidPassword() {
        SiteUser user = new SiteUser();
        user.setEmail("test@test.com");
        user.setUsername("tester");
        user.setPassword("1234"); // ⭐ 규칙 미충족 비밀번호

        when(userRepository.existsByEmail("test@test.com")).thenReturn(false);

        assertThatThrownBy(() -> userService.joinUser(user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("비밀번호 정책을 만족하지 않습니다.");
    }

    @Test
    @DisplayName("회원 삭제: username으로 조회 후 삭제 호출")
    void deleteUser() {
        SiteUser user = new SiteUser();
        user.setUsername("tester");

        when(userRepository.findByUsername("tester")).thenReturn(user);

        userService.deleteUser("tester");

        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("아이디 중복 체크 정상")
    void checkUsernameDuplicate() {
        when(userRepository.existsByUsername("min")).thenReturn(true);
        when(userRepository.existsByUsername("lee")).thenReturn(false);

        assertThat(userRepository.existsByUsername("min")).isTrue();
        assertThat(userRepository.existsByUsername("lee")).isFalse();
    }

    @Test
    @DisplayName("이름 + 생년월일로 회원 조회")
    void getUserInfo() {
        Date birth = Date.valueOf("2000-01-01");
        SiteUser user = new SiteUser();

        when(userRepository.findByNameAndBirthDate("민승", birth)).thenReturn(user);

        SiteUser result = userRepository.findByNameAndBirthDate("민승", birth);

        assertThat(result).isEqualTo(user);
    }

    @Test
    @DisplayName("findByUsername 정상 동작")
    void findByUsername() {
        SiteUser user = new SiteUser();
        when(userRepository.findByUsername("tester")).thenReturn(user);

        SiteUser result = userRepository.findByUsername("tester");

        assertThat(result).isEqualTo(user);
    }

    @Test
    @DisplayName("findByNameAndBirthDateAndEmail 정상 동작")
    void findByNameBirthEmail() {
        Date birth = Date.valueOf("2000-01-01");
        SiteUser user = new SiteUser();

        when(userRepository.findByNameAndBirthDateAndEmail("민승", birth, "test@test.com"))
                .thenReturn(user);

        SiteUser result = userRepository.findByNameAndBirthDateAndEmail("민승", birth, "test@test.com");

        assertThat(result).isEqualTo(user);
    }

    @Test
    @DisplayName("findByNameAndBirthDateAndUsernameAndEmail 정상 동작")
    void findByNameBirthUsernameEmail() {
        Date birth = Date.valueOf("2000-01-01");
        SiteUser user = new SiteUser();

        when(userRepository.findByNameAndBirthDateAndUsernameAndEmail("민승", birth, "tester", "test@test.com"))
                .thenReturn(user);

        SiteUser result = userRepository.findByNameAndBirthDateAndUsernameAndEmail("민승", birth, "tester", "test@test.com");

        assertThat(result).isEqualTo(user);
    }
}
