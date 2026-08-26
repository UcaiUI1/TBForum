package cn.tbforum.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 集中管理应用配置参数，消除散落代码中的魔法数字。
 * 通过 application.yml 的 app.* 前缀绑定。
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Upload upload = new Upload();
    private Tag tag = new Tag();
    private Moderator moderator = new Moderator();
    private Cache cache = new Cache();
    private View view = new View();
    private RateLimit rateLimit = new RateLimit();
    private Redis redis = new Redis();
    private String publicBaseUrl = "http://localhost:8089";

    public Upload getUpload() {
        return upload;
    }

    public Tag getTag() {
        return tag;
    }

    public Moderator getModerator() {
        return moderator;
    }

    public Cache getCache() {
        return cache;
    }

    public View getView() {
        return view;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Redis getRedis() {
        return redis;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setUpload(Upload u) {
        this.upload = u;
    }

    public void setTag(Tag t) {
        this.tag = t;
    }

    public void setModerator(Moderator m) {
        this.moderator = m;
    }

    public void setCache(Cache c) {
        this.cache = c;
    }

    public void setView(View v) {
        this.view = v;
    }

    public void setRateLimit(RateLimit r) {
        this.rateLimit = r;
    }

    public void setRedis(Redis r) {
        this.redis = r;
    }

    public void setPublicBaseUrl(String v) {
        this.publicBaseUrl = v;
    }

    /** 上传限制 */
    public static class Upload {
        private long maxFileSize = 2 * 1024 * 1024; // 2MB
        private int maxWidth = 4096;
        private int maxHeight = 4096;
        private long maxPixels = 16_777_216L;
        private long maxUserStorage = 50L * 1024 * 1024;
        private Duration pendingTtl = Duration.ofHours(24);
        private Duration cleanupInterval = Duration.ofHours(1);
        private int cleanupBatchSize = 100;
        private boolean legacyReconciliationEnabled = true;
        private int legacyReconciliationBatchSize = 200;

        public long getMaxFileSize() {
            return maxFileSize;
        }

        public int getMaxWidth() {
            return maxWidth;
        }

        public int getMaxHeight() {
            return maxHeight;
        }

        public long getMaxPixels() {
            return maxPixels;
        }

        public long getMaxUserStorage() {
            return maxUserStorage;
        }

        public Duration getPendingTtl() {
            return pendingTtl;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public boolean isLegacyReconciliationEnabled() {
            return legacyReconciliationEnabled;
        }

        public int getLegacyReconciliationBatchSize() {
            return legacyReconciliationBatchSize;
        }

        public void setMaxFileSize(long v) {
            this.maxFileSize = v;
        }

        public void setMaxWidth(int v) {
            this.maxWidth = v;
        }

        public void setMaxHeight(int v) {
            this.maxHeight = v;
        }

        public void setMaxPixels(long v) {
            this.maxPixels = v;
        }

        public void setMaxUserStorage(long v) {
            this.maxUserStorage = v;
        }

        public void setPendingTtl(Duration v) {
            this.pendingTtl = v;
        }

        public void setCleanupInterval(Duration v) {
            this.cleanupInterval = v;
        }

        public void setCleanupBatchSize(int v) {
            this.cleanupBatchSize = v;
        }

        public void setLegacyReconciliationEnabled(boolean v) {
            this.legacyReconciliationEnabled = v;
        }

        public void setLegacyReconciliationBatchSize(int v) {
            this.legacyReconciliationBatchSize = v;
        }
    }
    /** 标签限制 */
    public static class Tag {
        private int maxPerPost = 5;
        private int minLength = 2;
        private int maxLength = 30;

        public int getMaxPerPost() {
            return maxPerPost;
        }

        public int getMinLength() {
            return minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxPerPost(int v) {
            this.maxPerPost = v;
        }

        public void setMinLength(int v) {
            this.minLength = v;
        }

        public void setMaxLength(int v) {
            this.maxLength = v;
        }
    }
    /** 版主限制 */
    public static class Moderator {
        private int maxPerForum = 2;

        public int getMaxPerForum() {
            return maxPerForum;
        }

        public void setMaxPerForum(int v) {
            this.maxPerForum = v;
        }
    }
    /** 缓存 TTL */
    public static class Cache {
        private Duration feedTtl = Duration.ofMinutes(2);
        private Duration forumListTtl = Duration.ofMinutes(10);

        public Duration getFeedTtl() {
            return feedTtl;
        }

        public Duration getForumListTtl() {
            return forumListTtl;
        }

        public void setFeedTtl(Duration v) {
            this.feedTtl = v;
        }

        public void setForumListTtl(Duration v) {
            this.forumListTtl = v;
        }
    }
    /** 浏览数防刷 */
    public static class View {
        private Duration dedupWindow = Duration.ofMinutes(5);

        public Duration getDedupWindow() {
            return dedupWindow;
        }

        public void setDedupWindow(Duration v) {
            this.dedupWindow = v;
        }
    }
    /** 敏感端点限流与可信代理配置 */
    public static class RateLimit {
        private int maxBuckets = 10000;
        private Duration expireAfterAccess = Duration.ofMinutes(10);
        private List<String> trustedProxies = new ArrayList<>();

        public int getMaxBuckets() {
            return maxBuckets;
        }

        public Duration getExpireAfterAccess() {
            return expireAfterAccess;
        }

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setMaxBuckets(int v) {
            this.maxBuckets = v;
        }

        public void setExpireAfterAccess(Duration v) {
            this.expireAfterAccess = v;
        }

        public void setTrustedProxies(List<String> v) {
            this.trustedProxies = v == null ? new ArrayList<>() : new ArrayList<>(v);
        }
    }
    /** Redis 可选能力与故障冷却 */
    public static class Redis {
        private boolean enabled = false;
        private Duration failureCooldown = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public Duration getFailureCooldown() {
            return failureCooldown;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public void setFailureCooldown(Duration v) {
            this.failureCooldown = v;
        }
    }
}
