package com.mysite.sbb.mail;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MailController {
    private final MailService mailService;
    private final RedisTemplate<String, String> redisTemplate; // 이메일 인증 숫자를 저장하는 변수

    public MailController(MailService mailService, RedisTemplate<String, String> redisTemplate) {
        this.mailService = mailService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/mailSend")
    public ResponseEntity<String> mailSend(@RequestBody MailDto dto) {
        try {
            mailService.enqueueMail(dto.mail(), MailType.SIGNUP_VERIFY);
            return ResponseEntity.ok("인증메일 요청이 접수되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("오류가 발생하였습니다.");
        }
    }


    @PostMapping("/passwordMailSend")
    public ResponseEntity<String> passwordMailSend(@RequestBody MailDto dto) {
        try {
            mailService.enqueueMail(dto.mail(), MailType.PASSWORD_RESET);
            return ResponseEntity.ok("인증메일 요청이 접수되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("오류가 발생하였습니다.");
        }
    }


    // 인증번호 일치여부 확인
    @GetMapping("/mailCheck")
    public ResponseEntity<?> mailCheck(
            @RequestParam("email") String email,
            @RequestParam("authNum") String userNumber
    ) {
        String savedNum = redisTemplate.opsForValue().get(email);

        if (savedNum != null && savedNum.equals(userNumber)) {
            redisTemplate.delete(email);
            return ResponseEntity.status(HttpStatus.OK).body("인증이 완료되었습니다.");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("인증 실패: 번호가 일치하지 않습니다.");
        }
    }


}