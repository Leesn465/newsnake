package com.mysite.sbb.jwt;

public final class JwtConstants {
    private JwtConstants() {}

    public static final String SECRET_KEY = System.getenv().getOrDefault("JWT_SECRET", "newSnakeSecret");
    public static final long ACCESS_TOKEN_EXPIRATION_MILLIS = 60 * 60 * 1000L; // 1시간
    public static final String ISSUER = "https://www.newsnake.site";
    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_PREFIX = "Bearer ";
}