package com.mysite.sbb.config;


import com.mysite.sbb.jwt.JwtAuthenticationFilter;
import com.mysite.sbb.jwt.JwtAuthorizationFilter;
import com.mysite.sbb.jwt.JwtTokenProvider;
import com.mysite.sbb.jwt.Oauth.OAuth2LoginSuccessHandler;
import com.mysite.sbb.jwt.Oauth.PrincipalOauth2UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity

public class SecurityConfig {
    private final PrincipalOauth2UserService principalOauth2UserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    //    private final UserRepository userRepository;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final UserDetailsService userDetailsService;
    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(UserDetailsService userDetailsService, PrincipalOauth2UserService principalOauth2UserService,
                          OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler, AuthenticationConfiguration authenticationConfiguration,
                          JwtTokenProvider jwtTokenProvider) {
        this.principalOauth2UserService = principalOauth2UserService;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.authenticationConfiguration = authenticationConfiguration;
    }

    @Bean
    public AuthenticationManager authenticationManager() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }


    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    AuthenticationManager authenticationManager) throws Exception {

        JwtAuthenticationFilter jwtAuthFilter = new JwtAuthenticationFilter(authenticationManager, jwtTokenProvider);
        jwtAuthFilter.setFilterProcessesUrl("/api/login");

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // JWT 기반 인증이므로 서버에 세션을 저장하지 않음 (Stateless)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // =========================
                        // 🔓 비회원 허용 영역
                        // =========================
                        .requestMatchers(
                                // 로그인 / 회원가입 / OAuth
                                "/login/**",
                                "/api/login",
                                "/api/signup/**",
                                "/api/auth/**",
                                "/api/auth/exchange",
                                "/api/oauth2/**",
                                "/oauth2/**",
                                "/login/oauth2/**",

                                // 계정 보조 기능
                                "/api/IdFind",
                                "/api/pwFind",
                                "/api/mailCheck",
                                "/api/mailSend",
                                "/api/passwordMailSend",

                                // 공개 API (검색/랭킹/데이터 조회)
                                "/api/parse-news",
                                "/api/ranking",
                                "/api/stock-data/**",
                                "/api/ads",

                                // 분석/실시간
                                "/api/analyze-sse",
                                "/broadcast/**",
                                "/analyze/**",

                                // 세션 확인
                                "/api/session-user",
                                "/api/oauth2/session-user",

                                // Swagger
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",

                                // 소개글 조회
                                "/api/admin/info"
                        ).permitAll()

                        // =========================
                        // 🔐 관리자 권한
                        // =========================
                        .requestMatchers("/api/admin/info/update").authenticated()

                        // =========================
                        // 🔒 나머지는 USER 권한 필요
                        // =========================
                        .anyRequest().hasRole("USER")
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 로그인 요청 시 JSON 기반 인증을 처리하고 JWT를 발급하는 필터
                .addFilterAt(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // 매 요청마다 Authorization 헤더의 JWT를 검증하여 사용자 인증
                .addFilterBefore(new JwtAuthorizationFilter(authenticationManager, userDetailsService, jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .oauth2Login(oauth2 -> oauth2
                        .failureHandler((request, response, exception) -> {
                            // 서버 콘솔(로그)에 에러 원인을 출력합니다.
                            System.out.println("OAuth2 Failure Reason: " + exception.getMessage());
                            exception.printStackTrace();

                            String base = "https://www.newsnake.site";
                            // 에러 메시지를 쿼리 파라미터에 담아서 프론트에서 확인해봅시다.
                            String redirectUrl = base + "/login?error=" + java.net.URLEncoder.encode(exception.getMessage(), "UTF-8");
                            response.sendRedirect(redirectUrl);
                        })
                        .userInfoEndpoint(userInfo ->
                                userInfo.userService(principalOauth2UserService))
                        .successHandler(oAuth2LoginSuccessHandler)
                );

        return http.build();
    }


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedOrigin("https://www.newsnake.site");
        config.addAllowedOrigin("https://newsnake.site");
        // 로컬 React
//        config.addAllowedOrigin("http://localhost");        // Docker React
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        // Authorization 헤더를 클라이언트가 접근할 수 있도록 노출
        config.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }


    /// RestTemplate 빈을 생성 -> 기존 방식은
    /// RestTemplate을 로컬에서 바로 생성해서 쓰는 방식
    /// 이렇게 하면 빈 등록 없이도 사용 가능
    ///
    /// 다만 장점은 제한적임:
    ///
    /// 설정 공유 어려움 – 예: 타임아웃, 인터셉터, 메시지 컨버터
    ///
    /// 테스트, Mocking 어려움 – DI로 주입된 빈을 사용하면 단위 테스트에서 쉽게 Mock 가능
    ///
    /// 재사용성 낮음 – 여러 서비스에서 매번 new RestTemplate() 생성해야 함

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}