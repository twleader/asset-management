package com.steven.assets.srpp.mail;

import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.Collections; import org.springframework.beans.factory.annotation.Value; import org.springframework.http.MediaType; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;
/** Exact internal-only boundary; deliberately independent from every existing internal token. */
@Component public class SrppDailyReportInternalTokenFilter extends OncePerRequestFilter {
    public static final String BASE="/internal/srpp/daily-report-mail"; public static final String HEADER="X-SRPP-Daily-Report-Internal-Token";
    @Value("${srpp.daily-report.internal-token:}") private String token; @Value("${srpp.daily-report.owner-email:tw.leader@gmail.com}") private String owner;
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith(BASE);}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse p,FilterChain c)throws IOException,ServletException {if(!r.getRequestURI().equals(BASE)&&!r.getRequestURI().matches(BASE+"/[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")){error(p,404,"NOT_FOUND");return;}String got=r.getHeader(HEADER),gotOwner=r.getHeader("X-SRPP-Daily-Report-Owner");if(token==null||token.isBlank()||got==null||Collections.list(r.getHeaders(HEADER)).size()!=1||!same(token,got)){error(p,401,"TOKEN_REQUIRED");return;}if(gotOwner==null||!same(owner,gotOwner)){error(p,403,"OWNER_INVALID");return;}c.doFilter(r,p);}
    private static boolean same(String a,String b){try{return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(a.getBytes(StandardCharsets.UTF_8)),MessageDigest.getInstance("SHA-256").digest(b.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){return false;}} private static void error(HttpServletResponse p,int s,String c)throws IOException{p.setStatus(s);p.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);p.getWriter().write("{\"code\":\""+c+"\"}");}
}
