package com.mysite.sbb.jwt.Oauth.OAuth2UserInfo;

import java.util.Map;

public class NaverUserInfo implements OAuth2UserInfo {
    private final Map<String, Object> attributes; // 여기엔 response 맵이 들어감

    public NaverUserInfo(Map<String, Object> attributes) {
        // 네이버가 보내준 전체 맵에서 "response" 맵만 꺼내서 저장
        this.attributes = (Map<String, Object>) attributes.get("response");
    }


    @Override
    public String getProviderId() {
        return attributes.get("id").toString();
    }

    @Override
    public String getEmail() {
        return attributes.get("email").toString();
    }

    @Override
    public String getName() {
        return attributes.get("name").toString();
    }

    @Override
    public String getProvider() {
        return "naver";
    }
}
