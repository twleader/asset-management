package com.steven.assets.integration.fubon;

import com.steven.assets.service.fubon.*;
import org.junit.jupiter.api.Test;
import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FubonSettlementReceiptCaptureTest {
    @Test void payloadPreservesExplicitUnknownAndNoDataWithoutInventingZeros() {
        var noData=new FubonDtos.SettlementDay("NO_DATA_OBSERVED",DATE,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        var b=new FubonDtos.SettlementBatch(DATE,NOW,"synthetic-fingerprint",true,"SDK_RANGE_3D_RETURNED_ROWS",null,java.util.List.of(noData));
        var p=FubonSettlementReceiptCapture.payload(b);
        assertThat(p.path("details").get(0).path("settlementDate").isNull()).isTrue();
        assertThat(p.path("details").get(0).path("buyFee").isNull()).isTrue();
        assertThat(p.path("details").get(0).path("buyTax").isNull()).isTrue();
    }
    @Test void revokedOwnerCannotCaptureEitherOutcome() {
        var store=mock(FubonSettlementReceiptStore.class);var owners=mock(FubonSyncOwnerPort.class);
        doThrow(new FubonSyncOwnerPort.Rejected(FubonSyncOwnerPort.Denial.NO_OWNER)).when(owners).lockAndRevalidate(9L);
        var capture=new FubonSettlementReceiptCapture(store,owners);
        assertThatThrownBy(()->capture.unavailable(9L,NOW,FubonSettlementOutcome.SETTLEMENT_FAILED)).isInstanceOf(FubonSyncOwnerPort.Rejected.class);
        assertThatThrownBy(()->capture.verified(9L,settlement())).isInstanceOf(FubonSyncOwnerPort.Rejected.class);
        verifyNoInteractions(store);
    }
}
