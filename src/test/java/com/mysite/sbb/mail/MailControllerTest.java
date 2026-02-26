package com.mysite.sbb.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MailController.class)
class MailControllerTest {

    private final String testEmail = "test@example.com";
    private final String authNumber = "123456";
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private MailService mailService;
    @MockBean
    private RedisTemplate<String, String> redisTemplate;
    // RedisTemplate의 opsForValue()를 Mocking하기 위한 Mock 객체
    @MockBean
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("회원가입 메일 전송 성공")
    @WithMockUser
        // 👈 POST 요청에 인증 환경 추가
    void testMailSend_Success() throws Exception {
        // given
        String requestJson = "{\"mail\":\"" + testEmail + "\"}";

        // mailService.sendMail이 정상적으로 실행되도록 Mocking합니다.
        // sendMail이 void가 아니므로 doNothing()을 사용하지 않고,
        // 별도의 return 값을 설정할 필요도 없으므로 when 구문을 사용하지 않습니다.

        // when & then
        mockMvc.perform(post("/api/mailSend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(csrf())) // 👈 CSRF 토큰 추가
                .andExpect(status().isOk())
                .andExpect(content().string("인증번호가 전송되었습니다."));

        // mailService.sendMail이 호출되었는지 검증
        verify(mailService, times(1)).sendMail(testEmail, true);
    }

    @Test
    @DisplayName("회원가입 메일 전송 실패 (이미 등록된 이메일)")
    @WithMockUser
        // 👈 POST 요청에 인증 환경 추가
    void testMailSend_AlreadyRegistered() throws Exception {
        // given
        String requestJson = "{\"mail\":\"" + testEmail + "\"}";

        // mailService.sendMail이 IllegalArgumentException을 발생시키도록 Mocking (doThrow는 void/non-void 모두 가능)
        doThrow(new IllegalArgumentException("이미 등록된 이메일입니다."))
                .when(mailService).sendMail(testEmail, true);

        // when & then
        mockMvc.perform(post("/api/mailSend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(csrf())) // 👈 CSRF 토큰 추가
                .andExpect(status().isBadRequest()) // 👈 403 -> 400
                .andExpect(content().string("이미 등록된 이메일입니다."));
    }

    // ---

    @Test
    @DisplayName("비밀번호 재설정 메일 전송 성공")
    @WithMockUser
        // 👈 POST 요청에 인증 환경 추가
    void testPasswordMailSend_Success() throws Exception {
        // given
        String requestJson = "{\"mail\":\"" + testEmail + "\"}";

        // mailService.sendMail이 정상적으로 실행되도록 Mocking합니다.
        // (마찬가지로 doNothing() 대신 when 구문을 사용하지 않음)

        // when & then
        mockMvc.perform(post("/api/passwordMailSend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(csrf())) // 👈 CSRF 토큰 추가
                .andExpect(status().isOk())
                .andExpect(content().string("인증번호가 전송되었습니다."));

        verify(mailService, times(1)).sendMail(testEmail, false);
    }

    // ---

    @Test
    @DisplayName("인증번호 확인 성공")
    @WithMockUser
        // 👈 GET 요청에 인증 환경 추가 (302 해결)
    void testMailCheck_Success() throws Exception {
        // given
        // redisTemplate.opsForValue()가 Mock 객체를 반환하도록 설정
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // redisTemplate.opsForValue().get(email)이 저장된 번호를 반환하도록 Mocking
        when(valueOperations.get(testEmail)).thenReturn(authNumber);

        // when & then
        mockMvc.perform(get("/api/mailCheck")
                        .param("email", testEmail)
                        .param("authNum", authNumber))
                .andExpect(status().isOk()) // 👈 302 -> 200
                .andExpect(content().string("인증이 완료되었습니다."));

        // 인증 성공 후 redisTemplate.delete(email)이 호출되었는지 검증
        verify(redisTemplate, times(1)).delete(testEmail);
    }

    @Test
    @DisplayName("인증번호 불일치 실패")
    @WithMockUser
        // 👈 GET 요청에 인증 환경 추가 (302 해결)
    void testMailCheck_Mismatch() throws Exception {
        // given
        String wrongNumber = "999999";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // redisTemplate에는 123456이 저장되어 있다고 가정
        when(valueOperations.get(testEmail)).thenReturn(authNumber);

        // when & then
        mockMvc.perform(get("/api/mailCheck")
                        .param("email", testEmail)
                        .param("authNum", wrongNumber)) // 틀린 번호 전달
                .andExpect(status().isBadRequest()) // 👈 302 -> 400
                .andExpect(content().string("인증 실패: 번호가 일치하지 않습니다."));

        // 실패 시 redisTemplate.delete(email)은 호출되지 않았는지 검증
        verify(redisTemplate, never()).delete(testEmail);
    }

    @Test
    @DisplayName("인증번호 만료/없음 실패")
    @WithMockUser
        // 👈 GET 요청에 인증 환경 추가 (302 해결)
    void testMailCheck_Expired() throws Exception {
        // given
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // redisTemplate에 저장된 번호가 null (만료 또는 존재하지 않음)이라고 가정
        when(valueOperations.get(testEmail)).thenReturn(null);

        // when & then
        mockMvc.perform(get("/api/mailCheck")
                        .param("email", testEmail)
                        .param("authNum", authNumber))
                .andExpect(status().isBadRequest()) // 👈 302 -> 400
                .andExpect(content().string("인증 실패: 번호가 일치하지 않습니다."));

        // 실패 시 redisTemplate.delete(email)은 호출되지 않았는지 검증
        verify(redisTemplate, never()).delete(testEmail);
    }
}