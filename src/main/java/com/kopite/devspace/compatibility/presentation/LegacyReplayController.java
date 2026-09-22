package com.kopite.devspace.compatibility.presentation;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.auth.application.CurrentUserService;
import com.kopite.devspace.compatibility.application.ApiVersionRetiredException;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Authenticated tombstones; historical creation records remain available to normal API adapters. */
@Hidden @RestController @RequestMapping(value="/api/v1",produces="application/json")
@RequiredArgsConstructor
public class LegacyReplayController {
    private final CurrentUserService users;
    @PostMapping(value={"/projects","/tasks","/journals","/milestones","/links"},consumes="application/json")
    public void replay(Authentication auth,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        retired(auth);
    }
    @RequestMapping(value={"/projects","/tasks","/journals","/milestones","/links"},
        method={RequestMethod.GET,RequestMethod.HEAD,RequestMethod.PATCH,RequestMethod.PUT,RequestMethod.DELETE,RequestMethod.OPTIONS})
    public void retired(Authentication auth){users.resolve(((InternalUserPrincipal)auth.getPrincipal()).userId());throw new ApiVersionRetiredException();}
    @RequestMapping({"/projects/{id}","/projects/{id}/**","/tasks/{id}","/tasks/{id}/**","/journals/{id}","/milestones/{id}","/links/{id}","/overview","/dashboards/home"})
    public void retiredResource(Authentication auth){retired(auth);}
}
