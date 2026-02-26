package com.mysite.sbb.jwt.Oauth.OAuth2UserInfo;

import java.util.Map;

public class KakaoUserInfo implements OAuth2UserInfo{
    private final Map<String, Object> attributes; // 여기엔 response 맵이 들어감

    public KakaoUserInfo(Map<String, Object> attributes) {
        // 네이버가 보내준 전체 맵에서 "response" 맵만 꺼내서 저장
        this.attributes = attributes;
    }

    @Override
    public String getProviderId() {
        return attributes.get("id").toString();
    }

    @Override
    public String getEmail() {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        if (kakaoAccount == null) {
            return "kakao" + attributes.get("id").toString() + "@kakao.com";
        }
        String email = (String) kakaoAccount.get("email");
        return email != null ? email : "kakao" + attributes.get("id").toString() + "@kakao.com";
    }

    @Override
    public String getName() {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        if (kakaoAccount == null) return null;

        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
        if (profile == null) return null;

        return (String) profile.get("nickname");
    }

    @Override
    public String getProvider() {
        return "kakao";
    }

}
