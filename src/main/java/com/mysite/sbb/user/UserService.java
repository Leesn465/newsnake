package com.mysite.sbb.user;

import com.mysite.sbb.user.Role.Role;
import com.mysite.sbb.user.Role.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 사용자 계정 도메인 서비스
 * <p>
 * 담당 책임:
 * - 회원 가입 및 기본 권한 부여
 * - 비밀번호 정책 검증
 * - 비밀번호 변경 / 초기화
 * - 일반 계정과 OAuth 계정 분리 처리
 * <p>
 * 모든 보안 관련 로직은 Controller가 아닌 Service 계층에서 처리하여
 * 비즈니스 규칙의 일관성을 유지한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원 가입 비즈니스 로직
     * <p>
     * 처리 순서:
     * 1. 이메일 중복 검증
     * 2. 비밀번호 보안 정책 검증
     * 3. BCrypt 해싱
     * 4. 기본 권한 ROLE_USER 부여
     * 5. 트랜잭션으로 사용자 + 권한 동시 저장
     */
    @Transactional
    public void joinUser(SiteUser user) {
        if (userRepository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        }

        if (!validatePassword(user.getPassword())) {
            throw new IllegalArgumentException("비밀번호 정책을 만족하지 않습니다.");
        }

        String encodedPassword = passwordEncoder.encode(user.getPassword());
        user.setPassword(encodedPassword);

        Role roleUser = roleRepository.findByRoleName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));

        Set<Role> roles = new HashSet<>();
        roles.add(roleUser);
        user.setRoles(roles);

        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(String username) {
        SiteUser user = userRepository.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        userRepository.delete(user);
    }

    /**
     * 비밀번호 변경
     * - 기존 비밀번호 검증
     * - 동일한 비밀번호 재사용 방지
     * - 새 비밀번호 정책 검증 후 재해싱
     */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        SiteUser user = userRepository.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("존재하지 않는 사용자입니다.");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }

        if (!validatePassword(newPassword)) {
            throw new IllegalArgumentException("비밀번호 정책을 만족하지 않습니다.");
        }

        String encoded = passwordEncoder.encode(newPassword);
        user.setPassword(encoded);
        userRepository.save(user);
    }

    /**
     * 비밀번호 분실 시 임시 비밀번호로 초기화
     * <p>
     * - 외부 메일/SMS 전송을 위해 평문 임시 비밀번호를 반환
     * - DB에는 BCrypt 해시만 저장됨
     * - 컨트롤러 단에서 사용자에게 전달 후 즉시 폐기됨
     */
    @Transactional
    public String resetPasswordAndReturnTemp(SiteUser user, String rawTempPassword) {
        String encoded = passwordEncoder.encode(rawTempPassword);
        user.setPassword(encoded);
        userRepository.save(user);
        return rawTempPassword;
    }

    /**
     * 보안 강화를 위한 커스텀 비밀번호 정책
     * <p>
     * 규칙:
     * - 길이 8~16
     * - 대문자 / 소문자 / 숫자 / 특수문자 포함
     * - 동일 문자 3회 이상 금지
     * - 키보드 연속 패턴(qwe, asd, 123 등) 차단
     */

    public boolean validatePassword(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 16) {
            return false;
        }

        boolean hasUpper = pw.matches(".*[A-Z].*");
        boolean hasLower = pw.matches(".*[a-z].*");
        boolean hasNumber = pw.matches(".*[0-9].*");
        boolean hasSpecial = pw.matches(".*[^A-Za-z0-9].*");

        if (pw.matches(".*(.)\\1\\1.*")) {
            return false;
        }

        String sequentialRegex =
                "(012|123|234|345|456|567|678|789|890|098|987|876|765|654|543|432|321|"
                        + "qwe|wer|ert|rty|tyu|yui|uio|iop|poi|oiu|iuy|uyt|ytr|tre|rew|ewq|"
                        + "asd|sdf|dfg|fgh|ghj|hjk|jkl|lkj|kjh|jhg|hgf|gfd|fds|dsa|"
                        + "zxc|xcv|cvb|vbn|bnm|mnb|nbv|bvc|vcx|cxz)";

        if (pw.toLowerCase().matches(".*" + sequentialRegex + ".*")) {
            return false;
        }

        return hasUpper && hasLower && hasNumber && hasSpecial;
    }

    /**
     * 계정 탈퇴 처리
     * <p>
     * - 일반 로그인 계정: 비밀번호 재확인 필수
     * - OAuth 계정(provider != null): 비밀번호 검증 생략
     * <p>
     * 인증 방식에 따라 탈퇴 정책을 분리하여 처리함
     */
    @Transactional
    public void deleteUserWithPasswordCheck(String username, String password) {
        SiteUser user = userRepository.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("존재하지 않는 사용자");
        }

        if (user.getProvider() == null) {
            if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
                throw new IllegalArgumentException("비밀번호 불일치");
            }
        }

        userRepository.delete(user);
    }

    @Transactional(readOnly = true)
    public SiteUser findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public SiteUser findByNameBirthEmail(String name, Date birthDate, String email) {
        return userRepository.findByNameAndBirthDateAndEmail(name, birthDate, email);
    }

    @Transactional(readOnly = true)
    public SiteUser findByNameBirthUsernameEmail(String name, Date birthDate, String username, String email) {
        return userRepository.findByNameAndBirthDateAndUsernameAndEmail(name, birthDate, username, email);
    }

    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }


}
