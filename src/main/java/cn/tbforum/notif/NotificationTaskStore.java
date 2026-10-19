package cn.tbforum.notif;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Short atomic database operations; no database lock is held during broker/network I/O. */
@Repository
public class NotificationTaskStore {
    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;

    public NotificationTaskStore(JdbcTemplate jdbc, NotificationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record Claim(String id, long notificationId, String token, int attempts) {}

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    public List<String> due(Instant now) {
        return jdbc.queryForList("""
                select id from notification_tasks where
                (status in ('READY','QUEUED') and next_attempt_at <= ?)
                or (status = 'IN_FLIGHT' and lease_until <= ?)
                order by next_attempt_at, id limit ?
                """, String.class, ts(now), ts(now), properties.getBatchSize());
    }

    @Transactional
    public Claim claimDue(String id, Instant now) {
        if (!lockAvailable(id)) return null;
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update(
                """
                update notification_tasks set status='IN_FLIGHT', claim_token=?, lease_until=?, attempts=attempts+1
                where id=? and attempts < ? and
                ((status in ('READY','QUEUED') and next_attempt_at <= ?)
                or (status='IN_FLIGHT' and lease_until <= ?))
                """, token, ts(now.plus(properties.getLease())), id, properties.getMaxAttempts(), ts(now), ts(now));
        if (changed == 0) {
            jdbc.update("""
                    update notification_tasks set status='DEAD', claim_token=null, lease_until=null,
                    last_error='Delivery attempts exhausted during recovery'
                    where id=? and attempts >= ? and
                    ((status in ('READY','QUEUED') and next_attempt_at <= ?)
                    or (status='IN_FLIGHT' and lease_until <= ?))
                    """, id, properties.getMaxAttempts(), ts(now), ts(now));
            return null;
        }
        return loadClaim(id, token);
    }

    @Transactional
    public Claim claimQueued(String id, Instant now) {
        if (!lockAvailable(id)) return null;
        String token = UUID.randomUUID().toString();
        if (jdbc.update("""
                update notification_tasks set status='IN_FLIGHT', claim_token=?, lease_until=?
                where id=? and status='QUEUED'
                """, token, ts(now.plus(properties.getLease())), id) == 0) return null;
        return loadClaim(id, token);
    }

    private Claim loadClaim(String id, String token) {
        return jdbc.queryForObject(
                "select notification_id, attempts from notification_tasks where id=? and claim_token=?",
                (rs, row) -> new Claim(id, rs.getLong(1), token, rs.getInt(2)),
                id,
                token);
    }

    private boolean lockAvailable(String id) {
        // A slow completion transaction must not stall the relay or consume a lease while waiting for its lock.
        return !jdbc.queryForList(
                        "select id from notification_tasks where id=? for update skip locked", String.class, id)
                .isEmpty();
    }

    /** Commit QUEUED before publishing: a crash before XADD is recovered by the queue deadline. */
    public boolean queued(Claim claim, Instant now) {
        return jdbc.update("""
                update notification_tasks set status='QUEUED', next_attempt_at=?, lease_until=null
                where id=? and status='IN_FLIGHT' and claim_token=?
                """, ts(now.plus(properties.getQueueTimeout())), claim.id(), claim.token()) == 1;
    }

    public boolean sent(Claim claim, Instant now) {
        return jdbc.update("""
                update notification_tasks set status='SENT', completed_at=?, claim_token=null, lease_until=null, last_error=null
                where id=? and status='IN_FLIGHT' and claim_token=?
                """, ts(now), claim.id(), claim.token()) == 1;
    }

    public void failed(Claim claim, Instant now, Exception error, boolean publishing) {
        long multiplier = 1L << Math.min(20, Math.max(0, claim.attempts() - 1));
        long delay = Math.min(
                properties.getRetryMax().toMillis(), properties.getRetryBase().toMillis() * multiplier);
        String status = claim.attempts() >= properties.getMaxAttempts() ? "DEAD" : "READY";
        // Store only a classification, never broker URIs, credentials, payloads or exception messages.
        String reason = error.getClass().getSimpleName();
        String owner = publishing ? "status='QUEUED' and claim_token=?" : "status='IN_FLIGHT' and claim_token=?";
        jdbc.update(
                "update notification_tasks set status=?, next_attempt_at=?, claim_token=null, lease_until=null, last_error=? where id=? and "
                        + owner,
                status,
                ts(now.plusMillis(delay)),
                reason,
                claim.id(),
                claim.token());
    }

    public boolean replay(String id, Instant now) {
        return jdbc.update("""
                update notification_tasks set status='READY', attempts=0, next_attempt_at=?,
                claim_token=null, lease_until=null, last_error=null, completed_at=null where id=? and status='DEAD'
                """, ts(now), id) == 1;
    }

    public long count(String status) {
        return jdbc.queryForObject("select count(*) from notification_tasks where status=?", Long.class, status);
    }

    /** Delete delivery bookkeeping only; notifications and failed/uncompleted work remain intact. */
    public int purgeCompleted(Instant now) {
        return jdbc.update("""
                delete from notification_tasks where id in
                (select id from notification_tasks where status='SENT' and completed_at < ?
                 order by completed_at limit ?)
                """, ts(now.minus(properties.getCompletedRetention())), properties.getBatchSize());
    }

    public double oldestAge(Instant now) {
        Timestamp oldest = jdbc.queryForObject(
                "select min(created_at) from notification_tasks where status in ('READY','QUEUED','IN_FLIGHT')",
                Timestamp.class);
        return oldest == null ? 0 : Math.max(0, (now.toEpochMilli() - oldest.getTime()) / 1000.0);
    }
}
