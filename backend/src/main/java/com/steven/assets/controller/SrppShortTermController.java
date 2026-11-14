package com.steven.assets.controller;

import com.steven.assets.dto.SrppShortTermDto.*;
import com.steven.assets.service.srpp.settings.SrppShortTermService;
import org.springframework.web.bind.annotation.*;

/** Page reads only; no rule publication or trading operation is exposed. */
@RestController
@RequestMapping(value="/api/portfolio-advice/srpp/short-opportunities",produces=org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class SrppShortTermController {
    private final SrppShortTermService service;
    public SrppShortTermController(SrppShortTermService service){this.service=service;}
    @GetMapping public ListEnvelope list(@RequestParam(required=false) String draftId,@RequestParam(required=false) String revision){return service.list(draftId,revision);}
    @GetMapping("/stock") public DetailEnvelope stock(@RequestParam String stockCode,@RequestParam String market){return service.stock(stockCode,market);}
}
