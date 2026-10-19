package com.kopite.devspace.auth.infrastructure;

import com.kopite.devspace.auth.infrastructure.security.ApiAccessDeniedHandler;
import com.kopite.devspace.auth.infrastructure.security.ApiAuthenticationEntryPoint;
import com.kopite.devspace.auth.infrastructure.security.CurrentUserAuthenticationFilter;
import com.kopite.devspace.auth.infrastructure.oidc.OidcAuthenticationFailureHandler;
import com.kopite.devspace.auth.infrastructure.oidc.OidcAuthenticationSuccessHandler;
import com.kopite.devspace.auth.infrastructure.security.OriginValidationFilter;
import com.kopite.devspace.auth.infrastructure.oidc.SafeAuthorizationRequestResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean @org.springframework.core.annotation.Order(1)
    SecurityFilterChain githubWebhookSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(request -> "POST".equals(request.getMethod())
                        && request.getRequestURI().equals(request.getContextPath() + "/api/integrations/github/webhooks"))
                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-Token");
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SafeAuthorizationRequestResolver authorizationRequestResolver,
            OidcAuthenticationSuccessHandler authenticationSuccessHandler,
            OidcAuthenticationFailureHandler authenticationFailureHandler,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            CurrentUserAuthenticationFilter currentUserAuthenticationFilter,
            OriginValidationFilter originValidationFilter,
            org.springframework.core.env.Environment environment,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName
    ) throws Exception {
        RequestMatcher unauthenticatedLogout = request -> {
            if (!"POST".equalsIgnoreCase(request.getMethod())
                    || !request.getRequestURI().equals(request.getContextPath() + "/api/v1/auth/logout")) {
                return false;
            }
            var authentication = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication();
            return authentication == null
                    || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
        };

        http
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .requestCache(requestCache -> requestCache.disable())
                .authorizeHttpRequests(authorize -> {
                    // Default/production remains protected, even if a development profile is also active.
                    if (environment.matchesProfiles("(dev | development | local | test) & !prod & !production")) {
                        authorize.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    authorize.requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/callback/**",
                                "/oauth2/**",
                                "/login/oauth2/**"
                        ).permitAll()
                        .anyRequest().authenticated();
                })
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(unauthenticatedLogout))
                .oauth2Login(oauth2 -> oauth2
                        .securityContextRepository(securityContextRepository)
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(authorizationRequestResolver))
                        .redirectionEndpoint(endpoint -> endpoint
                                .baseUri("/api/v1/auth/callback/*"))
                        .successHandler(authenticationSuccessHandler)
                        .failureHandler(authenticationFailureHandler))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies(sessionCookieName)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(
                                org.springframework.http.HttpStatus.NO_CONTENT))
                        .permitAll())
                .sessionManagement(sessionManagement -> sessionManagement
                        .sessionFixation(sessionFixation -> sessionFixation.changeSessionId()))
                .addFilterAfter(currentUserAuthenticationFilter,
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterAfter(originValidationFilter, CurrentUserAuthenticationFilter.class);

        return http.build();
    }
}
