package com.steven.assets.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded projection maintenance only; never fetches providers or creates reports. */
@Component
public class CanonicalDividendProjectionPoller {
    private static final Logger log=LoggerFactory.getLogger(CanonicalDividendProjectionPoller.class);
    private final CanonicalDividendRepository repository;
    private final CanonicalDividendReconciliationService service;
    private final boolean enabled;
    private long watermark;
    private long retryAfter;
    public CanonicalDividendProjectionPoller(CanonicalDividendRepository repository,CanonicalDividendReconciliationService service,
            @Value("${dividend.canonical.projection.enabled:${DIVIDEND_CANONICAL_PROJECTION_ENABLED:false}}") boolean enabled){this.repository=repository;this.service=service;this.enabled=enabled;}
    @Scheduled(fixedDelay=60000) public synchronized void poll(){
        if(!enabled||System.currentTimeMillis()<retryAfter)return;
        try{var batch=repository.observationsAfter(watermark,100);for(var key:batch.keys()){
            var preview=service.preview(key.code(),key.market());service.apply(key.code(),key.market(),preview.planToken());
        }watermark=batch.throughId();retryAfter=0;}
        catch(RuntimeException e){retryAfter=System.currentTimeMillis()+120000;log.warn("股利資料投影暫停，水位保留以供重試：{}",e.getClass().getSimpleName());}
    }
}
