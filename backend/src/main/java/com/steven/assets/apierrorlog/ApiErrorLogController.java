package com.steven.assets.apierrorlog;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/api-error-logs")
public class ApiErrorLogController {
    private final ApiErrorLogService service;
    public ApiErrorLogController(ApiErrorLogService service){this.service=service;}
    @GetMapping public ApiErrorLogService.PageResult list(@RequestParam(defaultValue="ALL") String source,@RequestParam(required=false) String operationKey,@RequestParam(defaultValue="NEWEST") String sort,@RequestParam(defaultValue="0") int page){return service.list(source,operationKey,sort,page);}
    @GetMapping("/operations") public List<ApiErrorLogOperationCatalog.Operation> operations(@RequestParam(defaultValue="ALL") String source){return service.operations(source);}
    @GetMapping("/{id}") public ApiErrorLogService.Detail detail(@PathVariable Long id){return service.detail(id);}
}
