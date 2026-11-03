package com.steven.assets.service.srpp.us;

import java.time.*;
import java.util.*;

/** The HTTP reader can only select an immutable publication, never capture or update state. */
public interface UsReportReadPort {
    record Scope(long owner,String consumer,String market,LocalDate date,String slot,String policy,String swagger,String calculator){}
    record Saved(UUID id,Scope scope,String sourceHash,String inputHash,String contentHash,String content,Instant publishedAt){}
    Optional<Saved> latest(Scope scope);
    Optional<Saved> byId(long owner,UUID id);
}
