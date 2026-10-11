package com.steven.assets.integration.fubon;
import com.steven.assets.service.fubon.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
/** Revalidate selected ACTIVE configured-admin under row lock before each immutable append. */
@Component
public class FubonSrppSourceWriter {
    private final FubonSyncOwnerPort owners;private final FubonSrppSourceStore store;
    public FubonSrppSourceWriter(FubonSyncOwnerPort owners,FubonSrppSourceStore store){this.owners=owners;this.store=store;}
    @Transactional public void append(FubonSrppSourceStore.Observation observation){owners.lockAndRevalidate(observation.owner());store.append(observation);}
}
