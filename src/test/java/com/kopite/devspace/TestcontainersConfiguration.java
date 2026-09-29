package com.kopite.devspace;

import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    org.springframework.test.context.DynamicPropertyRegistrar documentStorage() throws IOException {
        var root = java.nio.file.Files.createTempDirectory("devspace-test-storage-");
        java.nio.file.Files.writeString(root.resolve(".devspace-document-storage"), "devspace-document-storage-v1");
        return registry -> {
            registry.add("app.storage.root", root::toString);
            registry.add("app.attachment.cleanup.enabled", () -> false);
        };
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16.4"));
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> browserValidationLogin(
            UserWorkspaceCreationService users,
            SecurityContextRepository securityContexts
    ) {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain
            ) throws ServletException, IOException {
                if (!"/browser-test/login".equals(request.getRequestURI())) {
                    chain.doFilter(request, response);
                    return;
                }
                var user = users.createOrReuse(
                        "browser-validation",
                        "project-api",
                        "Browser Validation"
                );
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        new InternalUserPrincipal(user.user().getId(), user.user().getDisplayName()),
                        null,
                        List.of()
                ));
                securityContexts.saveContext(context, request, response);
                String target = "flow".equals(request.getParameter("target"))
                        ? "/project-api-browser-validation.html"
                        : "/swagger-ui/index.html";
                response.sendRedirect(target);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Integer.MIN_VALUE);
        registration.addUrlPatterns("/browser-test/login");
        return registration;
    }

}
