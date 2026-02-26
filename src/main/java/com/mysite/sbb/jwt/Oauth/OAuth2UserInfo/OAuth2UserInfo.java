package com.mysite.sbb.jwt.Oauth.OAuth2UserInfo;

public interface OAuth2UserInfo {
    String getProviderId();

    String getProvider();

    String getEmail();

    String getName();

}
