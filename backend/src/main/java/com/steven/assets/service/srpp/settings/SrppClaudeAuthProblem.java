package com.steven.assets.service.srpp.settings;
public final class SrppClaudeAuthProblem extends RuntimeException {private final int status;private final String code;public SrppClaudeAuthProblem(int status,String code){super("登入作業無法完成，請重新確認登入狀態");this.status=status;this.code=com.steven.assets.dto.SrppClaudeAuthDto.safe(code);}public int status(){return status;}public String code(){return code;}}
