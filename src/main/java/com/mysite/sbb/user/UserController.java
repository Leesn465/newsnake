package com.mysite.sbb.user;

import com.mysite.sbb.jwt.Oauth.PrincipalDetails;
import com.mysite.sbb.util.ConversionUtil;
import com.mysite.sbb.util.PasswordRandom;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.sql.Date;
import java.text.ParseException;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<?> postUser(@RequestBody SiteUser user) {
        try {
            userService.joinUser(user);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("Authorization", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok("로그아웃 완료");
    }

    /**
     * 회원 탈퇴 API
     * <p>
     * - 일반 로그인 사용자: 비밀번호 재확인 필요
     * - OAuth 로그인 사용자: provider 기반으로 비밀번호 검증 생략
     */
    @DeleteMapping("/deleteUser")
    public ResponseEntity<?> deleteUser(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestBody(required = false) Map<String, Object> map) {

        if (principalDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인 안 되어 있음");
        }

        String username = principalDetails.getUsername();
        String password = map == null ? null : (String) map.get("password");

        try {
            userService.deleteUserWithPasswordCheck(username, password);
            return ResponseEntity.ok("삭제 완료");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
        }
    }

    @GetMapping("/session-user")
    public ResponseEntity<?> getSessionUser(@AuthenticationPrincipal PrincipalDetails principalDetails) {
        if (principalDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인 안 되어 있음");
        }

        String username = principalDetails.getUsername();
        SiteUser user = userService.findByUsername(username);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("존재하지 않는 사용자");
        }

        return ResponseEntity.ok(
                Map.of(
                        "username", username,
                        "isAdmin", user.isAdmin()
                )
        );
    }

    @GetMapping("/oauth2/session-user")
    public ResponseEntity<?> getOAuth2SessionUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인 안 되어 있음");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User oauthUser) {
            return ResponseEntity.ok(oauthUser.getAttributes());
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("OAuth2 로그인 사용자 아님");
    }

    @PostMapping("/IdFind")
    public ResponseEntity<?> IdFind(@RequestBody Map<String, Object> map) throws ParseException {
        String name = map.get("name").toString();
        String birthDateStr = map.get("birthDate").toString();
        String email = map.get("email").toString();

        ConversionUtil conUtil = new ConversionUtil();
        Date birthDate = conUtil.stringToDate(birthDateStr);

        SiteUser user = userService.findByNameBirthEmail(name, birthDate, email);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body("존재하지 않는 사용자");
        }
        return ResponseEntity.ok(user.getUsername());
    }

    /**
     * 비밀번호 찾기
     * <p>
     * - 사용자 실명, 생년월일, 아이디, 이메일 검증
     * - 임시 비밀번호 생성 후 DB에는 해시만 저장
     * - 평문 임시 비밀번호는 이 API 응답으로만 반환
     */
    @PostMapping("/pwFind")
    public ResponseEntity<?> pwFind(@RequestBody Map<String, Object> map) throws ParseException {
        String name = map.get("name").toString();
        String birthDateStr = map.get("birthDate").toString();
        String username = map.get("username").toString();
        String email = map.get("email").toString();

        ConversionUtil conUtil = new ConversionUtil();
        Date birthDate = conUtil.stringToDate(birthDateStr);

        SiteUser user = userService.findByNameBirthUsernameEmail(
                name, birthDate, username, email
        );

        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body("존재하지 않는 사용자");
        }

        PasswordRandom passwordRandom = new PasswordRandom();
        String tempPassword = passwordRandom.generateRandomPassword(10);

        userService.resetPasswordAndReturnTemp(user, tempPassword);
        return ResponseEntity.ok(tempPassword);
    }

    @GetMapping("signup/IdCheck/{id}")
    public ResponseEntity<?> checkIdDuplicate(@PathVariable("id") String id) {
        boolean exists = userService.existsByUsername(id);
        return ResponseEntity.ok(!exists);
    }

    @GetMapping("userData/{username}")
    public ResponseEntity<?> getUserData(@PathVariable("username") String username) {
        SiteUser user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("존재하지 않는 사용자");
        }
        return ResponseEntity.ok(user);
    }

    @PostMapping("/newPw")
    public ResponseEntity<?> setNewPassword(@RequestBody Map<String, Object> map) {
        String username = map.get("username").toString();
        String password = map.get("password").toString();
        String newPassword = map.get("newPassword").toString();

        try {
            userService.changePassword(username, password, newPassword);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg.contains("현재 비밀번호")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(msg);
            }
            if (msg.contains("비밀번호 정책")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("비밀번호 정책을 만족하지 않습니다. 8~16자, 대소문자/숫자/특수문자 포함, 연속/반복 금지.");
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(msg);
        }
    }
}
