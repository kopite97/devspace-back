package com.kopite.devspace.document.infrastructure;
import com.kopite.devspace.document.application.DocumentLimits;
import com.kopite.devspace.global.response.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.Map;
/** Runs after Spring Security; cap bytes before parsing or buffering a document JSON tree. */
@Component @RequiredArgsConstructor
public class DocumentBodyLimitFilter extends OncePerRequestFilter {
    private final DocumentLimits limits;private final JsonMapper json;
    protected boolean shouldNotFilter(HttpServletRequest r){return !(r.getMethod().equals("POST")||r.getMethod().equals("PUT")||r.getMethod().equals("DELETE"))||!(r.getRequestURI().startsWith("/api/v2/documents")||r.getRequestURI().matches("/api/v2/journals/[^/]+/documents")||(r.getMethod().equals("DELETE")&&r.getRequestURI().startsWith("/api/v2/attachments/")));}
    protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
        boolean empty=r.getMethod().equals("DELETE")||r.getRequestURI().endsWith("/publish");
        int max=empty?0:limits.bodyBytes();byte[] bytes=r.getContentLengthLong()>max?null:r.getInputStream().readNBytes(max+1);
        if(bytes==null||bytes.length>max){response.setStatus(empty?400:413);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");json.writeValue(response.getOutputStream(),ApiError.of(empty?"VALIDATION_ERROR":"PAYLOAD_TOO_LARGE",empty?"Request body must be empty":"Document request exceeds its byte limit",Map.of()));return;}
        chain.doFilter(new HttpServletRequestWrapper(r){
            public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON input");}};}
            public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response);
    }
}
