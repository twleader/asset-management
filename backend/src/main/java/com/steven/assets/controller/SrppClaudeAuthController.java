package com.steven.assets.controller;
import com.steven.assets.service.srpp.settings.SrppClaudeAuthService;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;
@RestController @RequestMapping(value="/api/portfolio-advice/srpp/ai-auth/claude",produces=MediaType.APPLICATION_JSON_VALUE)
public class SrppClaudeAuthController {
 private final SrppClaudeAuthService service;public SrppClaudeAuthController(SrppClaudeAuthService s){service=s;}
 private ResponseEntity<Object>call(String a,String b,String t,String f,String r){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.call(a,b,t,f,r));}
 @GetMapping("/state")public ResponseEntity<Object>state(@RequestHeader("X-SRPP-AI-Auth-Binding")String b,@RequestParam String toolId){return call("state",b,toolId,null,null);}
 @PostMapping(value="/start",consumes=MediaType.APPLICATION_JSON_VALUE)public ResponseEntity<Object>start(@RequestHeader("X-SRPP-AI-Auth-Binding")String b,@RequestBody String r){return call("start",b,null,null,r);}
 @GetMapping("/sessions/{flowId}")public ResponseEntity<Object>status(@RequestHeader("X-SRPP-AI-Auth-Binding")String b,@PathVariable String flowId){return call("status",b,null,flowId,null);}
 @PostMapping(value="/sessions/{flowId}/complete",consumes=MediaType.APPLICATION_JSON_VALUE)public ResponseEntity<Object>complete(@RequestHeader("X-SRPP-AI-Auth-Binding")String b,@PathVariable String flowId,@RequestBody String r){return call("complete",b,null,flowId,r);}
 @PostMapping(value="/sessions/{flowId}/cancel",consumes=MediaType.APPLICATION_JSON_VALUE)public ResponseEntity<Object>cancel(@RequestHeader("X-SRPP-AI-Auth-Binding")String b,@PathVariable String flowId,@RequestBody String r){return call("cancel",b,null,flowId,r);}
}
