package com.kopite.devspace.document.presentation;
import com.kopite.devspace.auth.application.InternalUserPrincipal;
import com.kopite.devspace.document.domain.DocumentException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import java.util.*;
public final class DocumentHttp {
    private DocumentHttp(){}
    public static UUID user(Authentication a){return ((InternalUserPrincipal)a.getPrincipal()).userId();}
    public static void query(HttpServletRequest r,String... allowed){var names=Set.of(allowed);r.getParameterMap().forEach((k,v)->{if(!names.contains(k)||v.length!=1||v[0].isEmpty())throw DocumentException.invalid(k);});}
    public static int limit(String value){try{if(!value.matches("[0-9]+"))throw DocumentException.invalid("limit");int n=Integer.parseInt(value);if(n<1||n>100)throw DocumentException.invalid("limit");return n;}catch(NumberFormatException e){throw DocumentException.invalid("limit");}}
}
