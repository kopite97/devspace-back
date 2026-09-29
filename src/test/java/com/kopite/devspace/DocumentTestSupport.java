package com.kopite.devspace;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class) @AutoConfigureMockMvc
abstract class DocumentTestSupport {
    @Autowired MockMvc mvc;@Autowired UserWorkspaceCreationService users;@Autowired JdbcTemplate jdbc;@Autowired JsonMapper json;
    @Value("${app.security.origin}") String origin;
    static final String D="/api/v2/documents";
    record Owner(UUID user,UUID workspace,MockHttpSession session,String csrf){}
    Owner owner()throws Exception {
        var o=users.createOrReuse("document-test",UUID.randomUUID().toString(),"Owner");var ctx=SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(new InternalUserPrincipal(o.user().getId(),"Owner"),null,List.of()));var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",ctx);
        var response=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse();assertEquals(200,response.getStatus());
        return new Owner(o.user().getId(),o.workspace().getId(),session,json.readTree(response.getContentAsString()).path("csrfToken").asString());
    }
    MockHttpServletResponse request(Owner o,org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request,String body,int expected)throws Exception {
        request.session(o.session()).header("Origin",origin).header("X-CSRF-Token",o.csrf());if(body!=null)request.contentType("application/json").content(body);
        var r=mvc.perform(request).andReturn().getResponse();assertEquals(expected,r.getStatus(),r.getContentAsString());assertEquals("no-store",r.getHeader("Cache-Control"));return r;
    }
    JsonNode tree(MockHttpServletResponse r)throws Exception{return json.readTree(r.getContentAsString());}
    long counter(Owner o){return jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,o.workspace());}
    String id(JsonNode n){return n.path("id").asString();}
    @Autowired com.kopite.devspace.project.application.command.ProjectCommandService projects;
    @Autowired com.kopite.devspace.journal.application.command.JournalCommandService journals;
    UUID journal(Owner o){
        var p=projects.create(o.user(),UUID.randomUUID().toString(),new com.kopite.devspace.project.application.command.CreateProjectCommand("Project",null,"Java",null,null,null));
        return journals.create(o.user(),UUID.randomUUID().toString(),new com.kopite.devspace.journal.application.command.CreateJournalCommand("Journal",p.id(),"Body","2026-01-01")).id();
    }
    String collection(UUID journal){return "/api/v2/journals/"+journal+"/documents";}
}
