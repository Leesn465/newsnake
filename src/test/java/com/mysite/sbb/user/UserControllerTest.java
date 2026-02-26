package com.mysite.sbb.user;

import com.mysite.sbb.fastapi.FastApiService;
import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import com.mysite.sbb.util.ConversionUtil;
import com.mysite.sbb.util.PasswordRandom;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.sql.Date;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserControllerTest {

    @Captor
    ArgumentCaptor<Cookie> cookieCaptor;
    @Mock
    private UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FastApiService fastApiService;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;
    @InjectMocks
    private UserController userController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // 회원가입
    @Test
    void testPostUser_succes() {
        SiteUser user = new SiteUser();
        doNothing().when(userService).joinUser(user);
        ResponseEntity<?> res = userController.postUser(user);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testPostUser_faild() {
        SiteUser user = new SiteUser();
        doThrow(new IllegalArgumentException("이미 등록된 이메일입니다.")).when(userService).joinUser(user);
        ResponseEntity<?> res = userController.postUser(user);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).isEqualTo("이미 등록된 이메일입니다.");
    }

    // 로그아웃
    @Test
    void testLogout_쿠키_만료() {
        ResponseEntity<?> res = userController.logout(response);
        verify(response).addCookie(cookieCaptor.capture());
        Cookie c = cookieCaptor.getValue();
        assertThat(c.getName()).isEqualTo("Authorization");
        assertThat(c.getMaxAge()).isEqualTo(0);
        assertThat(res.getBody()).isEqualTo("로그아웃 완료");
    }

    // 회원탈퇴: 일반회원(비번ok), 비번불일치, 소셜, 로그인X
    @Test
    void testDeleteUser_일반회원_성공() {
        PrincipalDetails principal = mock(PrincipalDetails.class);
        when(principal.getUsername()).thenReturn("u1");
        SiteUser user = new SiteUser();
        user.setProvider(null);
        user.setPassword("ENCODED");
        Map<String, Object> map = new HashMap<>();
        map.put("password", "pw");

        when(userRepository.findByUsername("u1")).thenReturn(user);
        when(passwordEncoder.matches("pw", "ENCODED")).thenReturn(true);

        ResponseEntity<?> res = userController.deleteUser(principal, map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);     // 200 OK
        verify(userService).deleteUser("u1");
    }

    @Test
    void testDeleteUser_일반회원_비번틀림() {
        PrincipalDetails principal = mock(PrincipalDetails.class);
        when(principal.getUsername()).thenReturn("u2");
        SiteUser user = new SiteUser();
        user.setProvider(null);
        user.setPassword("PW");
        Map<String, Object> map = Map.of("password", "wrong");
        when(userRepository.findByUsername("u2")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "PW")).thenReturn(false);

        ResponseEntity<?> res = userController.deleteUser(principal, map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);     // 401로 변경!
        assertThat(res.getBody()).isEqualTo("비밀번호 불일치");
    }

    @Test
    void testDeleteUser_SNS계정_성공() {
        PrincipalDetails principal = mock(PrincipalDetails.class);
        when(principal.getUsername()).thenReturn("sns");
        SiteUser user = new SiteUser();
        user.setProvider("google");
        when(userRepository.findByUsername("sns")).thenReturn(user);

        ResponseEntity<?> res = userController.deleteUser(principal, new HashMap<>());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(userService).deleteUser("sns");
    }

    @Test
    void testDeleteUser_비로그인() {
        ResponseEntity<?> res = userController.deleteUser(null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void testGetSessionUser_로그인없음() {
        ResponseEntity<?> res = userController.getSessionUser(null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // OAuth2 세션 유저조회
    @Test
    void testGetOAuth2SessionUser_정상() {
        Authentication auth = mock(Authentication.class);
        OAuth2User oauthUser = mock(OAuth2User.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(oauthUser);
        when(oauthUser.getAttributes()).thenReturn(Map.of("name", "홍길동"));
        ResponseEntity<?> res = userController.getOAuth2SessionUser(auth);
        assertThat(res.getBody()).isEqualTo(Map.of("name", "홍길동"));
    }

    @Test
    void testGetOAuth2SessionUser_미인증() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(false);
        ResponseEntity<?> res = userController.getOAuth2SessionUser(auth);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // 아이디 찾기
    @Test
    void testIdFind_성공() throws ParseException {
        Map<String, Object> map = Map.of("name", "홍길동", "birthDate", "1999-01-01", "email", "test@gmail.com");
        Date birthDate = new ConversionUtil().stringToDate("1999-01-01");
        SiteUser user = new SiteUser();
        user.setUsername("testuser");
        when(userRepository.findByNameAndBirthDateAndEmail("홍길동", birthDate, "test@gmail.com")).thenReturn(user);

        ResponseEntity<?> res = userController.IdFind(map);
        assertThat(res.getBody()).isEqualTo("testuser");
    }

    @Test
    void testIdFind_실패() throws ParseException {
        Map<String, Object> map = Map.of("name", "홍길동", "birthDate", "1999-01-01", "email", "bad@gmail.com");
        Date birthDate = new ConversionUtil().stringToDate("1999-01-01");
        when(userRepository.findByNameAndBirthDateAndEmail("홍길동", birthDate, "bad@gmail.com")).thenReturn(null);
        ResponseEntity<?> res = userController.IdFind(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    }

    // 비번 찾기 (PasswordRandom 인스턴스 사용!)
    @Test
    void testPwFind_성공() throws ParseException {
        Map<String, Object> map = Map.of("name", "김철수", "birthDate", "2000-01-02", "username", "u3", "email", "test@example.com");
        Date birthDate = new ConversionUtil().stringToDate("2000-01-02");
        SiteUser user = new SiteUser();
        when(userRepository.findByNameAndBirthDateAndUsernameAndEmail(
                "김철수", birthDate, "u3", "test@example.com"
        )).thenReturn(user);

        PasswordRandom passwordRandom = mock(PasswordRandom.class);
        when(passwordRandom.generateRandomPassword(10)).thenReturn("randompw");
        // 비밀번호 저장 mocking(생략 가능)
        when(userRepository.save(any())).thenReturn(user);

        // 컨트롤러 내부 코드도 인스턴스 방식(new PasswordRandom())으로 호출해야 함!
        // 이 부분만 실제 구현과 맞게 set!

        ResponseEntity<?> res = userController.pwFind(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isInstanceOf(String.class);
    }

    @Test
    void testPwFind_실패() throws ParseException {
        Map<String, Object> map = Map.of("name", "김철수", "birthDate", "2000-01-02", "username", "fail", "email", "wrong@example.com");
        Date birthDate = new ConversionUtil().stringToDate("2000-01-02");
        when(userRepository.findByNameAndBirthDateAndUsernameAndEmail("김철수", birthDate, "fail", "wrong@example.com")).thenReturn(null);
        ResponseEntity<?> res = userController.pwFind(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    }

    // 아이디 중복확인
    @Test
    void testCheckIdDuplicate_중복O() {
        when(userRepository.existsByUsername("dupe")).thenReturn(true);
        ResponseEntity<?> res = userController.checkIdDuplicate("dupe");
        assertThat(res.getBody()).isEqualTo(false);
    }

    @Test
    void testCheckIdDuplicate_중복X() {
        when(userRepository.existsByUsername("unique")).thenReturn(false);
        ResponseEntity<?> res = userController.checkIdDuplicate("unique");
        assertThat(res.getBody()).isEqualTo(true);
    }

    // 유저데이터 조회
    @Test
    void testGetUserData_성공() {
        SiteUser user = new SiteUser();
        when(userRepository.existsByUsername("who")).thenReturn(user.isAdmin());
        ResponseEntity<?> res = userController.getUserData("who");
        assertThat(res.getBody()).isEqualTo(user);
    }

    // 새 비번 설정(성공/실패)
    @Test
    void testSetNewPassword_성공() {
        Map<String, Object> map = Map.of(
                "username", "abc",
                "password", "oldpw",
                "newPassword", "Newpw12!"
        );
        SiteUser user = new SiteUser();
        user.setPassword("HASHED");

        when(userService.findByUsername("abc")).thenReturn(user);
        when(passwordEncoder.matches("oldpw", "HASHED")).thenReturn(true);
        when(userService.validatePassword("Newpw12!")).thenReturn(true); // 정책 통과
        when(userRepository.save(user)).thenReturn(user);

        ResponseEntity<?> res = userController.setNewPassword(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK); // 200
    }

    @Test
    void testSetNewPassword_비밀번호정책불만족() {
        Map<String, Object> map = Map.of(
                "username", "abc",
                "password", "oldpw",
                "newPassword", "short"
        );
        SiteUser user = new SiteUser();
        user.setPassword("HASHED");

        when(userService.findByUsername("abc")).thenReturn(user);
        when(passwordEncoder.matches("oldpw", "HASHED")).thenReturn(true);   // 반드시 true
        when(userService.validatePassword("short")).thenReturn(false);

        ResponseEntity<?> res = userController.setNewPassword(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);  // 400
        assertThat(res.getBody()).isEqualTo("비밀번호 정책을 만족하지 않습니다. 8~16자, 대소문자/숫자/특수문자 포함, 연속/반복 금지.");
    }

    @Test
    void testSetNewPassword_기존비밀번호불일치() {
        Map<String, Object> map = Map.of(
                "username", "abc",
                "password", "failpw",
                "newPassword", "Newpw12!"
        );
        SiteUser user = new SiteUser();
        user.setPassword("HASHED");

        when(userService.findByUsername("abc")).thenReturn(user);
        when(passwordEncoder.matches("failpw", "HASHED")).thenReturn(false);

        ResponseEntity<?> res = userController.setNewPassword(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isEqualTo("비밀번호 불일치");
    }

    @Test
    void testSetNewPassword_사용자없음() {
        Map<String, Object> map = Map.of(
                "username", "nouser",
                "password", "pw",
                "newPassword", "Newpw12!"
        );
        when(userService.findByUsername("nouser")).thenReturn(null);

        ResponseEntity<?> res = userController.setNewPassword(map);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isEqualTo("존재하지 않는 사용자");
    }
}