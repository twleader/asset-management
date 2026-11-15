package com.steven.assets.bff.portfolioadvice;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.core.oidc.user.OidcUser;import org.springframework.web.server.ServerWebExchange;import reactor.core.publisher.Mono;
@RestController @RequestMapping(value="/api/bff/portfolio-advice/srpp/ai-auth/claude",produces=MediaType.APPLICATION_JSON_VALUE)
public class SrppClaudeAuthBffController {
 private final SrppClaudeAuthBffService service;public SrppClaudeAuthBffController(SrppClaudeAuthBffService s){service=s;}
 private Mono<ResponseEntity<Object>>call(ServerWebExchange e,OidcUser p,String a,String t,String f,String r){return service.call(e,p,a,t,f,r).map(v->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v));}
 @GetMapping("/state")public Mono<ResponseEntity<Object>>state(ServerWebExchange e,@AuthenticationPrincipal OidcUser p,@RequestParam String toolId){return call(e,p,"state",toolId,null,null);}
 @PostMapping(value="/start",consumes=MediaType.APPLICATION_JSON_VALUE)public Mono<ResponseEntity<Object>>start(ServerWebExchange e,@AuthenticationPrincipal OidcUser p,@RequestBody String r){return call(e,p,"start",null,null,r);}
 @GetMapping("/sessions/{flowId}")public Mono<ResponseEntity<Object>>status(ServerWebExchange e,@AuthenticationPrincipal OidcUser p,@PathVariable String flowId){return call(e,p,"status",null,flowId,null);}
 @PostMapping(value="/sessions/{flowId}/complete",consumes=MediaType.APPLICATION_JSON_VALUE)public Mono<ResponseEntity<Object>>complete(ServerWebExchange e,@AuthenticationPrincipal OidcUser p,@PathVariable String flowId,@RequestBody String r){return call(e,p,"complete",null,flowId,r);}
 @PostMapping(value="/sessions/{flowId}/cancel",consumes=MediaType.APPLICATION_JSON_VALUE)public Mono<ResponseEntity<Object>>cancel(ServerWebExchange e,@AuthenticationPrincipal OidcUser p,@PathVariable String flowId,@RequestBody String r){return call(e,p,"cancel",null,flowId,r);}
}
