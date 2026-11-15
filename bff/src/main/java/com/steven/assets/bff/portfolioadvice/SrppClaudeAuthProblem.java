package com.steven.assets.bff.portfolioadvice;
public final class SrppClaudeAuthProblem extends RuntimeException {final int status;final String code;public SrppClaudeAuthProblem(int s,String c){super("登入作業無法完成，請重新確認登入狀態");status=s;code=SrppClaudeAuthDto.safe(c);}}
