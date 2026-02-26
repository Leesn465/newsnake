package com.mysite.sbb.jwt.Oauth;

import com.mysite.sbb.jwt.Oauth.OAuth2UserInfo.GoogleUserInfo;
import com.mysite.sbb.jwt.Oauth.OAuth2UserInfo.KakaoUserInfo;
import com.mysite.sbb.jwt.Oauth.OAuth2UserInfo.NaverUserInfo;
import com.mysite.sbb.jwt.Oauth.OAuth2UserInfo.OAuth2UserInfo;
import com.mysite.sbb.user.Role.Role;
import com.mysite.sbb.user.Role.RoleRepository;
import com.mysite.sbb.user.SiteUser;
import com.mysite.sbb.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


/**
 * OAuth2 로그인 시
 * Google / Naver / Kakao 사용자 정보를 통합하여
 * 내부 사용자(SiteUser)로 매핑하고,
 * <p>
 * 최초 로그인 시 자동 회원가입을 수행하는 서비스.
 * <p>
 * OAuth 계정과 내부 계정의 일관성을 유지하기 위해
 * email을 기준으로 사용자 식별을 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrincipalOauth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final @Lazy PasswordEncoder passwordEncoder;

    public OAuth2User loadOAuth2User(OAuth2UserRequest userRequest) {
        return super.loadUser(userRequest);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        log.info("🌐 OAuth 로그인 시도 - provider: {}", userRequest.getClientRegistration().getRegistrationId());

        OAuth2User oAuth2User = loadOAuth2User(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        log.info("🔍 OAuth attributes: {}", attributes);

        // OAuth provider(Google, Naver, Kakao)마다
        // 응답 JSON 구조가 다르므로
        // Provider별 UserInfo Adapter로 통합한다.
        OAuth2UserInfo oAuth2UserInfo = switch (provider) {
            case "google" -> new GoogleUserInfo(attributes);
            case "naver" -> new NaverUserInfo(attributes);
            case "kakao" -> new KakaoUserInfo(attributes);
            default -> throw new IllegalArgumentException("지원하지 않는 provider: " + provider);
        };

        String email = oAuth2UserInfo.getEmail();
        if (email == null) {
            log.error(" 이메일 정보를 가져올 수 없음 - provider: {}", provider);
            throw new RuntimeException("email_not_found");
        }
        // OAuth에서는 provider마다 user id가 다르기 때문에
        // email을 글로벌 사용자 식별자로 사용한다.
        Optional<SiteUser> optionalUser = Optional.ofNullable(userRepository.findByEmail(email));
        SiteUser user;

        // 최초 OAuth 로그인 사용자는
        // 내부 계정이 없으므로 자동으로 회원가입 처리
        if (optionalUser.isEmpty()) {
            log.info(" 신규 OAuth2 사용자 자동 등록: {}", email);
            Role userRole = roleRepository.findByRoleName("ROLE_USER")
                    .orElseThrow(() -> new RuntimeException("기본 ROLE_USER가 존재하지 않습니다."));



            /*
              동일 이메일이
              Google / Naver / Kakao에서
              서로 다른 계정으로 충돌하지 않도록
              provider를 suffix로 붙여 username 생성
             */
            String username = email.split("@")[0] + "_" + provider;

            // OAuth 계정은 비밀번호 로그인을 사용하지 않으므로
            // 보안을 위해 랜덤 UUID 기반 비밀번호를 부여


            String randomPassword = passwordEncoder.encode(UUID.randomUUID().toString());

            user = SiteUser.builder()
                    .username(username)
                    .name(oAuth2UserInfo.getName())
                    .password(randomPassword)
                    .email(email)
                    // OAuth 신규 가입자도 일반 회원과 동일하게
                    // 기본 ROLE_USER 권한을 부여
                    .roles(Set.of(userRole))
                    .provider(provider)
                    .providerId(oAuth2UserInfo.getProviderId())
                    .createDate(new Timestamp(System.currentTimeMillis()))
                    .build();

            userRepository.save(user);
            log.info(" 자동 회원가입 완료: {}", username);
        } else {
            user = optionalUser.get();
            log.info(" 기존 OAuth2 사용자 로그인: {}", user.getUsername());
        }

        return new PrincipalDetails(user, oAuth2User.getAttributes());
    }
}