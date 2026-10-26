package com.steven.assets.service.port;

import com.steven.assets.dto.CrawlerExportPathDto.RunNowResponse;
public interface CrawlerRunPort {
    enum Operation { EXPORT, FETCH_AND_EXPORT, PUBLIC_RESCAN }
    RunNowResponse run(Operation operation, long timeoutSeconds, RunNowResponse running);
}
