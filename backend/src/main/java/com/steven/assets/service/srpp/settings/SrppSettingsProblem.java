package com.steven.assets.service.srpp.settings;

public final class SrppSettingsProblem extends RuntimeException {
    private final int status;
    private final String code;
    public SrppSettingsProblem(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    public int status(){return status;}
    public String code(){return code;}
}
