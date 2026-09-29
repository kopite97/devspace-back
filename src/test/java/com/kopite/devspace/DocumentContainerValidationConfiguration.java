package com.kopite.devspace;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
/** Only packaged into an external validation fixture JAR; never in the production bootJar. */
@Configuration(proxyBeanMethods=false) @Profile("document-container-validation")
public class DocumentContainerValidationConfiguration {
    @Bean FilterRegistrationBean<OncePerRequestFilter> containerValidationLogin(UserWorkspaceCreationService users,SecurityContextRepository sessions) {
        var filter=new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
            var user=users.createOrReuse("document-container-validation","persistent-owner","Container owner");
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(user.user().getId(),"Container owner"),null,List.of()));
            sessions.saveContext(context,request,response);response.sendRedirect("/swagger-ui/index.html");
        }};
        var registration=new FilterRegistrationBean<OncePerRequestFilter>(filter);registration.setOrder(Integer.MIN_VALUE);registration.addUrlPatterns("/browser-test/login");return registration;
    }
}
