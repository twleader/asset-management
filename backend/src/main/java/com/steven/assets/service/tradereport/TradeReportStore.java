package com.steven.assets.service.tradereport;
import java.util.Optional;
public interface TradeReportStore {
 record Receipt(long id,String rawSha256) {}
 record Evidence(long id,String factsHash,String canonicalFacts,Long transactionId) {}
 Optional<Receipt> receipt(Long owner,String messageId);
 Optional<Evidence> evidence(Long owner,String identityHash);
 long addReceipt(Long owner,String messageId,String rawSha256);
 long addEvidence(Long owner,String identityHash,String factsHash,String canonicalFacts,Long transactionId);
 void link(long receipt,long evidence);
}
