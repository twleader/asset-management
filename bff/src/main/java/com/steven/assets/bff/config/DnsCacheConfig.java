package com.steven.assets.bff.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * BFF 上游 DNS 正向快取上限（Task 208）。
 *
 * <p><b>問題：</b>{@code docker compose up -d --force-recreate business-services} 後該容器會換 IP
 * （實測 172.19.0.4 → 172.19.0.7），BFF 卻持續對舊 IP 連線得 {@code Connection refused}，
 * 前端每個 {@code /api/**} 都回 500，且不會在數十秒內自癒（實測 3.5 分鐘後仍在報錯）。
 *
 * <p><b>根因：</b>reactor-netty 的 {@link HttpClient} 預設<b>不走 JDK {@code InetAddress}</b>，而是 netty 的
 * 非同步 DNS resolver（{@code DnsAddressResolverGroup}）。其 {@code cacheMaxTimeToLive} 預設為
 * {@code Integer.MAX_VALUE} 秒＝完全照抄 DNS 回應自帶的 TTL，而 Docker 內建 DNS（{@code 127.0.0.11}）
 * 對 container name 回的 A record TTL 實測為 <b>600 秒</b>，於是舊 IP 最久被記住 10 分鐘。
 * 容器 OS 的 {@code getent hosts} 正常，是因為那走 glibc/NSS，與 netty 自己的快取是兩套獨立機制。
 *
 * <p><b>因此：</b>{@code -Dnetworkaddress.cache.ttl} 這類 JVM 旗標對本路徑<b>完全無效</b>
 * （那是 JDK resolver 的旋鈕，且它其實是 security property 而非 system property，直接 -D 讀不到），
 * 唯一有效的施力點是把 netty resolver 的快取上限壓下來，見 {@link #MAX_TTL}。
 *
 * <p><b>兩條上游路徑都要套：</b>BFF 打到 business-services 的 {@code HttpClient} 有兩個互不共用的實例——
 * <ul>
 *   <li>gateway route（各 {@code *BffRoutes} 的 {@code .uri(businessServicesUrl)}）
 *       → 本類別的 {@link HttpClientCustomizer} bean（{@code HttpClientFactory.createInstance()}
 *       在最後一步才套 customizer，故必定蓋過預設值）；</li>
 *   <li>aggregation 用的 {@code businessServicesClient} WebClient
 *       → 由 {@link WebClientConfig} 呼叫 {@link #applyDnsCacheLimit(HttpClient, String)} 明確套用。</li>
 * </ul>
 * 只修其中一條會漏掉另一條（本次事故兩條都中：gateway 的 {@code /api/snapshots} 與 WebClient 的
 * {@code /api/bff/dashboard/enrich-dividend-rates} 同時報錯）。
 */
@Configuration
public class DnsCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(DnsCacheConfig.class);

    /**
     * DNS 正向快取上限，與 JDK {@code InetAddress} 的慣用值一致。
     *
     * <p>取 30 秒而非更短，是刻意的取捨：壓得越短自癒越快，但也等於越頻繁依賴 Docker 內建 DNS 的可用性
     * （netty 預設查詢逾時 5 秒，DNS 抖動會變成使用者可見的延遲）。相對修補前的最長 10 分鐘，
     * 30 秒已是量級改善，而對 embedded DNS 的查詢量遠低於秒級設定。
     */
    public static final Duration MAX_TTL = Duration.ofSeconds(30);

    /**
     * 對指定 {@link HttpClient} 套用 DNS 快取上限，並留下啟動日誌痕跡。
     *
     * <p>負向快取（解析失敗）刻意<b>不設</b>：netty 預設即為 0 秒＝不記住失敗。若設成 1 秒反而會把
     * 「容器重建瞬間必然出現的查無主機」黏住，正好黏在我們想加速的那個時間窗。
     *
     * <p>注意：日誌只證明「設定程式碼有被執行」，不等於證明最終服務流量的 client 就是這個實例；
     * 真正的驗收是「重建 business-services 後 BFF 是否於 {@link #MAX_TTL} 內跟上新 IP」的實測。
     *
     * @param httpClient 待設定的 reactor-netty client（immutable builder，回傳新實例）
     * @param usage      用途標記，僅供日誌辨識（{@code gateway} / {@code webclient}）
     */
    public static HttpClient applyDnsCacheLimit(HttpClient httpClient, String usage) {
        log.info("[dns-cache] {} 上游 DNS 正向快取上限 maxTtl={}s（Docker 內建 DNS 回 600s，不壓最久卡 10 分鐘）",
                usage, MAX_TTL.getSeconds());
        return httpClient.resolver(spec -> spec
                .cacheMinTimeToLive(Duration.ZERO)
                .cacheMaxTimeToLive(MAX_TTL));
    }

    /** Spring Cloud Gateway 上游 {@link HttpClient} 的唯一客製點。 */
    @Bean
    public HttpClientCustomizer gatewayDnsCacheCustomizer() {
        return httpClient -> applyDnsCacheLimit(httpClient, "gateway");
    }
}
