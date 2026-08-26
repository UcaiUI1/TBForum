package cn.tbforum.security;

import cn.tbforum.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * 安全解析客户端 IP。只有直接连接方属于显式配置的可信代理网段时，
 * 才会使用 X-Forwarded-For / X-Real-IP，防止客户端伪造代理头绕过限流。
 */
@Component
public class ClientIpResolver {
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(AppProperties properties) {
        this.trustedProxies = properties.getRateLimit().getTrustedProxies().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (remoteAddress == null) {
            return "unknown";
        }
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String resolved = resolveForwardedChain(forwardedFor, remoteAddress);
            if (resolved != null) {
                return resolved;
            }
            // 可信代理传入了畸形链时安全降级到对端地址，不继续信任其他头。
            return remoteAddress;
        }

        String realIp = normalize(request.getHeader("X-Real-IP"));
        return realIp != null ? realIp : remoteAddress;
    }

    private String resolveForwardedChain(String forwardedFor, String remoteAddress) {
        String[] parts = forwardedFor.split(",", -1);
        List<String> chain = new ArrayList<>(parts.length + 1);
        for (String part : parts) {
            String address = normalize(part);
            if (address == null) {
                return null;
            }
            chain.add(address);
        }
        chain.add(remoteAddress);

        for (int index = chain.size() - 1; index >= 0; index--) {
            String address = chain.get(index);
            if (!isTrustedProxy(address)) {
                return address;
            }
        }
        return chain.get(0);
    }

    private boolean isTrustedProxy(String address) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String address = value.trim();
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        int zoneIndex = address.indexOf('%');
        if (zoneIndex >= 0) {
            address = address.substring(0, zoneIndex);
        }
        if (address.isEmpty() || !address.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
