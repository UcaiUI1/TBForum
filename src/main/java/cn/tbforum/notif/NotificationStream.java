package cn.tbforum.notif;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** A single consumer group distributes work; the existing broadcast reaches every socket-owning instance. */
@Component
public class NotificationStream {
    static final String GROUP = "notification-dispatch";
    private static final DefaultRedisScript<Long> ENSURE_GROUP = new DefaultRedisScript<>("""
            local r = redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
            if type(r) == 'table' and r.err and not string.find(r.err, 'BUSYGROUP') then
                return redis.error_reply(r.err)
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            return redis.call('XDEL', KEYS[1], ARGV[2])
            """, Long.class);
    private final StringRedisTemplate redis;
    private final NotificationProperties properties;

    public NotificationStream(ObjectProvider<StringRedisTemplate> redis, NotificationProperties properties) {
        this.redis = redis.getIfAvailable();
        this.properties = properties;
    }

    private StreamOperations<String, String, String> ops() {
        if (redis == null) throw new IllegalStateException("Redis stream unavailable");
        return redis.opsForStream();
    }

    public void publish(String taskId) {
        if (ops().add(properties.getStreamKey(), Map.of("taskId", taskId)) == null)
            throw new IllegalStateException("Redis stream did not accept record");
    }

    public List<MapRecord<String, String, String>> read(String consumer) {
        ops();
        redis.execute(ENSURE_GROUP, List.of(properties.getStreamKey()), GROUP);
        return ops().read(
                        Consumer.from(GROUP, consumer),
                        StreamReadOptions.empty().count(properties.getBatchSize()),
                        StreamOffset.create(properties.getStreamKey(), ReadOffset.lastConsumed()));
    }

    public List<MapRecord<String, String, String>> reclaim(String consumer) {
        var pending = ops().pending(properties.getStreamKey(), GROUP, Range.unbounded(), properties.getBatchSize());
        var ids = pending.stream()
                .filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(properties.getLease()) >= 0)
                .map(PendingMessage::getId)
                .toArray(RecordId[]::new);
        return ids.length == 0
                ? List.of()
                : ops().claim(properties.getStreamKey(), GROUP, consumer, properties.getLease(), ids);
    }

    public void acknowledge(RecordId id) {
        redis.execute(REMOVE, List.of(properties.getStreamKey()), GROUP, id.getValue());
    }
}
