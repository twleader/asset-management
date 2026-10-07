package com.steven.assets.service.srpp;
import org.springframework.http.HttpStatus;
public class SrppCaptureProblem extends RuntimeException {
    public final HttpStatus status; public final String code;
    public SrppCaptureProblem(HttpStatus status, String code) { super(code); this.status=status; this.code=code; }
}
