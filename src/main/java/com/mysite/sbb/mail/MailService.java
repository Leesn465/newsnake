package com.mysite.sbb.mail;

import com.mysite.sbb.user.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * 이메일 인증 및 비밀번호 초기화 메일 서비스
 * <p>
 * 역할:
 * - 인증번호 생성 및 Redis(TTL) 저장
 * - 메일 발송 요청을 Redis Stream 큐에 등록
 * - 실제 메일 생성 및 발송 로직 제공
 * <p>
 * 구조:
 * - 인증번호: Redis Key-Value (3분 TTL)
 * - 메일 작업 큐: Redis Stream(mail:stream)
 * - 실제 전송은 MailWorker가 비동기 수행
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private static final String senderEmail = "lsm71103186";
    private static final String senderName = "WeGoHigh 팀";
    private final JavaMailSender javaMailSender;
    private final RedisTemplate<String, String> redisTemplate;
    private final UserRepository userRepository;

    // 랜덤으로 숫자 생성
    public int createNumber() {
        return (int) (Math.random() * (90000)) + 100000; //(int) Math.random() * (최댓값-최소값+1) + 최소값
    }

    public MimeMessage signUpCheckMail(String mail) {

        MimeMessage message = javaMailSender.createMimeMessage();

        if (userRepository.existsByEmail(mail)) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        } else {
            message = CreateMail(mail);
        }
        return message;
    }

    public MimeMessage passwordCheckMail(String mail) {
        if (!userRepository.existsByEmail(mail)) {
            throw new IllegalArgumentException("가입된 이메일이 아닙니다.");
        }
        return CreateMail(mail);

    }

    /**
     * 메일 발송 요청을 Redis Stream 큐에 등록
     * <p>
     * - 회원가입 / 비밀번호 재설정 요청을 비동기 처리
     * - SMTP 블로킹으로부터 API를 분리
     * - jobId로 메일 발송 작업 추적 가능
     */
    public String enqueueMail(String email, MailType type) {
        // 검증 로직은 여기서 미리 해도 됨 (빠르게 fail)
        if (type == MailType.SIGNUP_VERIFY && userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        }
        if (type == MailType.PASSWORD_RESET && !userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("가입된 이메일이 아닙니다.");
        }

        String jobId = java.util.UUID.randomUUID().toString();

        Map<String, String> fields = new HashMap<>();
        fields.put("jobId", jobId);
        fields.put("type", type.name());
        fields.put("email", email);
        fields.put("retry", "0");
        fields.put("createdAt", String.valueOf(System.currentTimeMillis()));

        redisTemplate.opsForStream().add("mail:stream", fields);
        return jobId;
    }

    /**
     * 메일 본문 생성 + 인증번호 발급
     * <p>
     * - 인증번호 생성 후 Redis에 TTL(3분)으로 저장
     * - 동일 이메일에 대해 최신 코드만 유효
     */
    public MimeMessage CreateMail(String mail) {
        int number = createNumber();
        redisTemplate.opsForValue().set(mail, String.valueOf(number), 3, TimeUnit.MINUTES); // 3분 만료
        MimeMessage message = javaMailSender.createMimeMessage();


        try {
            message.setFrom(new InternetAddress(senderEmail, senderName, "UTF-8"));
            message.setRecipients(MimeMessage.RecipientType.TO, mail);
            message.setSubject("이메일 인증");
            String body = "";
            body += "<h3>" + "요청하신 인증 번호입니다." + "</h3>";
            body += "<h1>" + number + "</h1>";
            body += "<h3>" + "감사합니다." + "</h3>";
            message.setText(body, "UTF-8", "html");
        } catch (MessagingException e) {
            throw new RuntimeException("메일 생성 중 오류가 발생했습니다.", e);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("메일 발신자 정보 인코딩 오류가 발생했습니다.", e);
        }
        return message;
    }

    /**
     * 실제 메일 발송
     * <p>
     * - Redis에 저장된 인증번호를 메일로 전송
     * - 인증번호는 프론트엔드에서 입력 받아 검증
     * <p>
     * 이 메서드는 MailWorker(비동기 워커)에서 호출됨
     */
    public int sendMail(String mail, boolean signUpCheck) {
        MimeMessage message;
        if (signUpCheck) {
            message = signUpCheckMail(mail);
        } else {
            message = passwordCheckMail(mail);
        }

        javaMailSender.send(message);

        String code = redisTemplate.opsForValue().get(mail);
        return code == null ? -1 : Integer.parseInt(code);
    }
}