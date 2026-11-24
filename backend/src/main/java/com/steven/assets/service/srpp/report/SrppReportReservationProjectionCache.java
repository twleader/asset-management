package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.SrppReportRepository.ReservationRef;
import com.steven.assets.srpp.SrppJcs;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Process-local cache of fully verified immutable projections, never a new financial authority. */
final class SrppReportReservationProjectionCache {
    static final int MAX_ENTRIES=262_144;
    static final int MAX_OWNER_ENTRIES=65_536;
    static final long MAX_WEIGHT=96L*1024*1024;
    static final long KEY_WEIGHT=512;
    record Key(long owner,UUID id,String metadataSha256) {}
    record Stats(long hits,long misses,long admissionRejected,int entries,long bytes) {}
    private record Entry(byte[] canonical,long weight) {}
    private final int maxEntries,maxOwnerEntries;
    private final long maxWeight;
    private final Map<Key,Entry> entries=new HashMap<>();
    private final Map<Long,Integer> ownerEntries=new HashMap<>();
    private long weight,hits,misses,admissionRejected;

    SrppReportReservationProjectionCache(){this(MAX_ENTRIES,MAX_OWNER_ENTRIES,MAX_WEIGHT);}
    SrppReportReservationProjectionCache(int maxEntries,int maxOwnerEntries,long maxWeight){
        if(maxEntries<1||maxOwnerEntries<1||maxOwnerEntries>maxEntries||maxWeight<KEY_WEIGHT)throw new IllegalArgumentException("INVALID_RESERVATION_CACHE_LIMITS");
        this.maxEntries=maxEntries;this.maxOwnerEntries=maxOwnerEntries;this.maxWeight=maxWeight;
    }
    static Key key(ReservationRef ref){
        var s=ref.scope();ObjectNode metadata=JsonNodeFactory.instance.objectNode();
        metadata.put("id",ref.id().toString()).put("sharedId",ref.sharedId().toString()).put("owner",s.owner())
                .put("date",s.date().toString()).put("slot",s.slot()).put("policy",s.policy()).put("swagger",s.swagger()).put("calculator",s.calculator())
                .put("consumer",ref.consumer()).put("stateHash",ref.stateHash()).put("inputHash",ref.inputHash()).put("contentHash",ref.contentHash())
                .put("status",ref.status()).put("publishedAt",ref.publishedAt().toString());
        return new Key(s.owner(),ref.id(),SrppJcs.hash(metadata));
    }
    ObjectNode get(Key key,Supplier<ObjectNode> verifiedProjection){
        byte[] hit;
        synchronized(this){Entry existing=entries.get(key);hit=existing==null?null:existing.canonical();if(hit==null)misses++;else hits++;}
        if(hit!=null)return decode(hit);
        // Validation and repository IO are outside the global lock; failed loaders cannot be admitted.
        byte[] canonical=SrppJcs.canonicalize(verifiedProjection.get()).getBytes(StandardCharsets.UTF_8);
        long candidateWeight=KEY_WEIGHT+canonical.length;
        synchronized(this){
            Entry raced=entries.get(key);
            if(raced!=null)return decode(raced.canonical());
            int count=ownerEntries.getOrDefault(key.owner(),0);
            if(entries.size()>=maxEntries||count>=maxOwnerEntries||candidateWeight>maxWeight-weight)admissionRejected++;
            else{entries.put(key,new Entry(canonical,candidateWeight));ownerEntries.put(key.owner(),count+1);weight+=candidateWeight;}
        }
        return decode(canonical);
    }
    /** Bounded initial candidates: the scan removes observed keys and never appends history. */
    synchronized Set<Key> ownerKeys(long owner){
        Set<Key> keys=new HashSet<>();for(Key key:entries.keySet())if(key.owner()==owner)keys.add(key);return keys;
    }
    /** Call only after the complete all-history traversal succeeded; new admissions are not candidates. */
    synchronized void pruneMissing(long owner,Set<Key> missing){
        for(Key key:missing)if(key.owner()==owner){
            Entry removed=entries.remove(key);
            if(removed!=null){weight-=removed.weight();ownerEntries.compute(owner,(ignored,count)->count==null||count<=1?null:count-1);}
        }
    }
    synchronized Stats stats(){return new Stats(hits,misses,admissionRejected,entries.size(),weight);}
    private static ObjectNode decode(byte[] bytes){return (ObjectNode)SrppJcs.parseStrict(new String(bytes,StandardCharsets.UTF_8));}
}
