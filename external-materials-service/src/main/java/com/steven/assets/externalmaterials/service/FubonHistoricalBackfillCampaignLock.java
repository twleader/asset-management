package com.steven.assets.externalmaterials.service;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

/** Holds one PostgreSQL session advisory lock for an entire campaign invocation. */
@Component
public final class FubonHistoricalBackfillCampaignLock {
    private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(hashtextextended(?::text, 466))";
    private static final String UNLOCK = "SELECT pg_advisory_unlock(hashtextextended(?::text, 466))";
    private final DataSource dataSource;

    public FubonHistoricalBackfillCampaignLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Lease> tryAcquire(UUID campaignId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK)) {
                statement.setString(1, campaignId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(new Lease(connection, campaignId));
        } catch (Exception failure) {
            closeQuietly(connection);
            throw new IllegalStateException("CAMPAIGN_LOCK_FAILED", failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final UUID campaignId;
        private boolean closed;

        private Lease(Connection connection, UUID campaignId) {
            this.connection = connection;
            this.campaignId = campaignId;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                try (PreparedStatement statement = connection.prepareStatement(UNLOCK)) {
                    statement.setString(1, campaignId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1))
                            throw new IllegalStateException("CAMPAIGN_UNLOCK_REJECTED");
                    }
                }
            } catch (Exception failure) {
                try { connection.abort(Runnable::run); }
                catch (Exception abortFailure) { closeQuietly(connection); }
                throw failure instanceof IllegalStateException state ? state
                        : new IllegalStateException("CAMPAIGN_UNLOCK_FAILED", failure);
            }
            try { connection.close(); }
            catch (Exception failure) { throw new IllegalStateException("CAMPAIGN_LOCK_CONNECTION_CLOSE_FAILED", failure); }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (Exception ignored) { }
    }
}
