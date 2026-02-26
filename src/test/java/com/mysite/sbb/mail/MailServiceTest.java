package com.mysite.sbb.mail;

import com.mysite.sbb.user.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MailServiceTest {

    @Mock
    private JavaMailSender javaMailSender;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private MailService mailService;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("createNumber(): 100000~999999 범위 확인")
    void createNumberRange() {
        int number = mailService.createNumber();
        assertThat(number).isBetween(100000, 999999);
    }

    @Test
    @DisplayName("signUpCheckMail(): 중복 이메일이면 예외 발생")
    void signUpCheckMailDuplicateEmail() {
        when(userRepository.existsByEmail("test@test.com")).thenReturn(true);

        assertThatThrownBy(() -> mailService.signUpCheckMail("test@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 등록된 이메일입니다.");
    }

    @Test
    @DisplayName("signUpCheckMail(): 정상 호출 시 MimeMessage 반환")
    void signUpCheckMailSuccess() {
        when(userRepository.existsByEmail("test@test.com")).thenReturn(false);

        MimeMessage mockMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(mockMessage);

        MimeMessage result = mailService.signUpCheckMail("test@test.com");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("passwordCheckMail(): 존재하지 않는 이메일 → 예외 발생")
    void passwordCheckMailFail() {
        when(userRepository.existsByEmail("none@test.com")).thenReturn(false);

        assertThatThrownBy(() -> mailService.passwordCheckMail("none@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("가입된 이메일이 아닙니다.");
    }

    @Test
    @DisplayName("passwordCheckMail(): 정상 호출 시 MimeMessage 반환")
    void passwordCheckMailSuccess() {
        when(userRepository.existsByEmail("aaa@test.com")).thenReturn(true);

        MimeMessage mockMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(mockMessage);

        MimeMessage result = mailService.passwordCheckMail("aaa@test.com");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("CreateMail(): redis에 인증번호 저장 및 MimeMessage 생성")
    void createMailTest() {
        MimeMessage mockMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(mockMessage);

        MimeMessage result = mailService.CreateMail("aaa@test.com");

        assertThat(result).isNotNull();
        verify(redisTemplate.opsForValue())
                .set(eq("aaa@test.com"), anyString(), eq(3L), eq(TimeUnit.MINUTES));
    }


    @Test
    @DisplayName("sendMail(): 회원가입 인증 메일 → signUpCheckMail() 호출 확인")
    void sendMailSignUp() {
        MimeMessage mockMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(mockMessage);

        when(userRepository.existsByEmail("aaa@test.com")).thenReturn(false);
        when(redisTemplate.opsForValue().get("aaa@test.com")).thenReturn("123456");

        int result = mailService.sendMail("aaa@test.com", true);

        verify(javaMailSender).send(any(MimeMessage.class));
        assertThat(result).isEqualTo(123456);
    }

    @Test
    @DisplayName("sendMail(): 비밀번호 찾기 인증 메일 → passwordCheckMail() 호출 확인")
    void sendMailPasswordCheck() {
        MimeMessage mockMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(mockMessage);

        when(userRepository.existsByEmail("aaa@test.com")).thenReturn(true);
        when(redisTemplate.opsForValue().get("aaa@test.com")).thenReturn("987654");

        int result = mailService.sendMail("aaa@test.com", false);

        verify(javaMailSender).send(any(MimeMessage.class));
        assertThat(result).isEqualTo(987654);
    }

}
