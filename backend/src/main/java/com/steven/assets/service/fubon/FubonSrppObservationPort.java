package com.steven.assets.service.fubon;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** Called solely by the gated background observer, never a report request/capture. */
public interface FubonSrppObservationPort {
    ObjectNode observe();
}
