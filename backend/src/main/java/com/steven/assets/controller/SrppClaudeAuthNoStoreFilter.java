package com.steven.assets.controller;
import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import org.springframework.stereotype.Component;import org.springframework.core.Ordered;import org.springframework.core.annotation.Order;import org.springframework.web.filter.OncePerRequestFilter;
/** Authentication responses must never be cached, including binding and routing failures. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE) public final class SrppClaudeAuthNoStoreFilter extends OncePerRequestFilter {
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{String path=request.getRequestURI();if(path.equals("/api/portfolio-advice/srpp/ai-auth/claude")||path.startsWith("/api/portfolio-advice/srpp/ai-auth/claude/"))response.setHeader("Cache-Control","no-store");chain.doFilter(request,response);}
}
