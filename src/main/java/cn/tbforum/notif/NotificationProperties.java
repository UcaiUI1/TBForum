package cn.tbforum.notif;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.notifications")
public class NotificationProperties {
    private boolean workersEnabled = true;
    private boolean consumerEnabled = true;
    private String streamKey = "tbforum:notifications:delivery";
    private Duration pollInterval = Duration.ofMillis(500);
    private Duration lease = Duration.ofSeconds(30);
    private Duration queueTimeout = Duration.ofSeconds(60);
    private Duration retryBase = Duration.ofSeconds(2);
    private Duration retryMax = Duration.ofMinutes(5);
    private int maxAttempts = 8;
    private int batchSize = 50;
    private Duration completedRetention = Duration.ofDays(14);

    @PostConstruct
    void validate() {
        if (streamKey == null
                || !streamKey.matches("[a-zA-Z0-9:_-]{1,120}")
                || batchSize < 1
                || batchSize > 200
                || maxAttempts < 1
                || maxAttempts > 100
                || invalid(pollInterval)
                || pollInterval.toMillis() < 100
                || pollInterval.compareTo(Duration.ofMinutes(1)) > 0
                || invalid(lease)
                || lease.toMillis() < 1000
                || lease.compareTo(Duration.ofMinutes(5)) > 0
                || invalid(queueTimeout)
                || queueTimeout.compareTo(lease) < 0
                || invalid(retryBase)
                || retryBase.toMillis() < 100
                || invalid(retryMax)
                || invalid(completedRetention)
                || completedRetention.compareTo(Duration.ofDays(1)) < 0
                || completedRetention.compareTo(Duration.ofDays(365)) > 0
                || retryMax.compareTo(Duration.ofDays(1)) > 0
                || retryMax.compareTo(retryBase) < 0) {
            throw new IllegalArgumentException("Invalid app.notifications configuration");
        }
    }

    private boolean invalid(Duration value) {
        return value == null || value.isNegative() || value.isZero();
    }

    public boolean isWorkersEnabled() {
        return workersEnabled;
    }

    public void setWorkersEnabled(boolean v) {
        workersEnabled = v;
    }

    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    public void setConsumerEnabled(boolean v) {
        consumerEnabled = v;
    }

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String v) {
        streamKey = v;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration v) {
        pollInterval = v;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration v) {
        lease = v;
    }

    public Duration getQueueTimeout() {
        return queueTimeout;
    }

    public void setQueueTimeout(Duration v) {
        queueTimeout = v;
    }

    public Duration getRetryBase() {
        return retryBase;
    }

    public void setRetryBase(Duration v) {
        retryBase = v;
    }

    public Duration getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Duration v) {
        retryMax = v;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int v) {
        maxAttempts = v;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public Duration getCompletedRetention() {
        return completedRetention;
    }

    public void setCompletedRetention(Duration value) {
        completedRetention = value;
    }

    public void setBatchSize(int v) {
        batchSize = v;
    }
}
