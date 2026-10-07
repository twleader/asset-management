package com.steven.assets.repository;

import com.steven.assets.model.SrppDailyReportMail;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SrppDailyReportMailRepository extends JpaRepository<SrppDailyReportMail, String> {
    @Modifying @Query("update SrppDailyReportMail m set m.leaseId=:lease, m.leaseExpiresAt=:until, m.updatedAt=:now where m.idempotencyKey=:key and m.state='PROCESSING' and m.leaseExpiresAt < :now")
    int reclaimProcessing(@Param("key") String key, @Param("lease") UUID lease, @Param("until") Instant until, @Param("now") Instant now);
    @Modifying @Query("update SrppDailyReportMail m set m.leaseId=:lease, m.leaseExpiresAt=:until, m.state='PROCESSING', m.updatedAt=:now where m.idempotencyKey=:key and m.state='FAILED'")
    int retryFailed(@Param("key") String key, @Param("lease") UUID lease, @Param("until") Instant until, @Param("now") Instant now);
    @Modifying @Query("update SrppDailyReportMail m set m.state='SUBMITTING', m.updatedAt=:now where m.idempotencyKey=:key and m.state='PROCESSING' and m.leaseId=:lease")
    int beginSubmitting(@Param("key") String key, @Param("lease") UUID lease, @Param("now") Instant now);
    @Modifying @Query("update SrppDailyReportMail m set m.state='OUTCOME_UNKNOWN', m.updatedAt=:now where m.idempotencyKey=:key and m.state='SUBMITTING' and m.leaseExpiresAt < :now")
    int expireSubmitting(@Param("key") String key, @Param("now") Instant now);
    @Modifying @Query("update SrppDailyReportMail m set m.state='SENT', m.messageId=:messageId, m.sentAt=:now, m.fromAddress=:from, m.toAddress=:to, m.subject=:subject, m.htmlSha256=:html, m.textSha256=:text, m.updatedAt=:now where m.idempotencyKey=:key and m.state='SUBMITTING' and m.leaseId=:lease")
    int markSent(@Param("key") String key, @Param("lease") UUID lease, @Param("messageId") String messageId, @Param("now") Instant now, @Param("from") String from, @Param("to") String to, @Param("subject") String subject, @Param("html") String html, @Param("text") String text);
    @Modifying @Query("update SrppDailyReportMail m set m.state='OUTCOME_UNKNOWN', m.updatedAt=:now where m.idempotencyKey=:key and m.state='SUBMITTING' and m.leaseId=:lease")
    int markUnknown(@Param("key") String key, @Param("lease") UUID lease, @Param("now") Instant now);
    @Modifying @Query("update SrppDailyReportMail m set m.state='FAILED', m.updatedAt=:now where m.idempotencyKey=:key and m.state='PROCESSING' and m.leaseId=:lease")
    int markFailedBeforeSubmit(@Param("key") String key, @Param("lease") UUID lease, @Param("now") Instant now);
    @Modifying @Query("delete from SrppDailyReportMail m where m.state='SENT' and m.sentAt < :cutoff")
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
