package com.example.integration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Secures the API endpoints with an API key (X-API-Key header).
 *
 * <p>All {@code /api/v1/**} endpoints (agent, agents, servers) are secured.
 * Swagger/OpenAPI paths are left open. When no API key is configured, all endpoints
 * are permitted (relies on network-level security).
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${agent.api-key:}") String apiKey) throws Exception {

        if (apiKey == null || apiKey.isBlank()) {
            // No API key configured — rely on network-level security
            http.securityMatcher("/api/v1/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            var apiKeyFilter = new ApiKeyAuthenticationFilter(apiKey);
            http.securityMatcher("/api/v1/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        }

        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.csrf(csrf -> csrf.disable());
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        // Allow specific front-end origins in production; wildcard for development
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "https://*.your-frontend-domain.com"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
