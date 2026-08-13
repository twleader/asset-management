package com.steven.assets.bff.exchangerate;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Docker host 本機工具可匿名讀取的唯一 USD/TWD API。 */
@RestController
@RequestMapping("/api/public/exchange-rate/usd-twd")
@RequiredArgsConstructor
public class PublicUsdTwdController {

    private final UsdTwdPublicService service;

    @GetMapping
    public Mono<UsdTwdPublicDto.Response> getUsdTwd() {
        return service.getUsdTwd();
    }
}
