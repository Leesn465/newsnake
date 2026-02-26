package com.mysite.sbb.jwt.Oauth;

import com.mysite.sbb.user.SiteUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Getter

public class PrincipalDetails implements UserDetails, OAuth2User {

    private final SiteUser siteUser;
    private final Map<String, Object> attributes;

    public PrincipalDetails(SiteUser user, Map<String, Object> attributes) {
        this.siteUser = user;
        this.attributes = attributes == null ? Collections.emptyMap() : attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return siteUser.getRoles().stream()
                .map(r -> (GrantedAuthority) () -> r.getRoleName())
                .toList();
    }

    @Override
    public String getPassword() {
        return siteUser.getPassword();
    }

    @Override
    public String getUsername() {
        return siteUser.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // OAuth2User
    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        return siteUser.getUsername();
    }
}