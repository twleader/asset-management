package com.steven.assets.service.srpp.settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.SrppClaudeAuthDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.UserAdminService;
import org.springframework.stereotype.Service;
@Service public class SrppClaudeAuthService {
 private final CurrentUserContext current;private final UserAdminService users;private final SrppClaudeAuthPort port;
 public SrppClaudeAuthService(CurrentUserContext current,UserAdminService users,SrppClaudeAuthPort port){this.current=current;this.users=users;this.port=port;}
 public Object call(String action,String binding,String toolId,String flowId,String raw){
 var owner=users.configuredAdmin().filter(u->u.isActive()&&u.isAdmin()&&u.getId()!=null&&u.getId().equals(current.getEffectiveUserId())&&current.isAdmin()&&"ACTIVE".equals(current.getStatus())&&users.isConfiguredAdmin(u.getEmail())).orElseThrow(()->new SrppClaudeAuthProblem(403,"AI_UNAUTHORIZED"));
 if(!SrppClaudeAuthDto.opaque(binding))throw new SrppClaudeAuthProblem(400,"AI_INVALID_REQUEST");
 ObjectNode body=SrppClaudeAuthDto.JSON.createObjectNode().put("ownerId",owner.getId()).put("sessionBinding",binding);
 try {if(action.equals("state")||action.equals("start")){if(action.equals("start")){JsonNode n=SrppClaudeAuthDto.parse(raw);SrppClaudeAuthDto.keys(n,"toolId");toolId=SrppClaudeAuthDto.text(n,"toolId");}if(!SrppClaudeAuthDto.tool(toolId))throw new IllegalArgumentException();body.put("toolId",toolId);}else{if(!SrppClaudeAuthDto.opaque(flowId))throw new IllegalArgumentException();body.put("flowId",flowId);if(action.equals("complete")){JsonNode n=SrppClaudeAuthDto.parse(raw);SrppClaudeAuthDto.keys(n,"code");String code=SrppClaudeAuthDto.text(n,"code");if(!SrppClaudeAuthDto.validCode(code))throw new SrppClaudeAuthProblem(400,"AUTH_INVALID_CODE");body.put("code",code);}else if(action.equals("cancel"))SrppClaudeAuthDto.keys(SrppClaudeAuthDto.parse(raw));} }catch(SrppClaudeAuthProblem e){throw e;}catch(Exception e){throw new SrppClaudeAuthProblem(400,"AI_INVALID_REQUEST");}
 JsonNode response=port.call(action,body);try{if(action.equals("state")){var state=SrppClaudeAuthDto.local(response);if(!state.toolId().equals(toolId))throw new IllegalArgumentException();return state;}var flow=SrppClaudeAuthDto.flow(response);if(flowId!=null&&!flow.flowId().equals(flowId)||toolId!=null&&!flow.toolId().equals(toolId))throw new IllegalArgumentException();return flow;}catch(Exception e){throw new SrppClaudeAuthProblem(502,"AUTH_FAILED");}
 }
}
