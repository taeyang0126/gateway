package com.lei.gateway.core.security;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.NetUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 客户端真实 IP 解析器。
 */
public class ClientIpResolver {

    private final CidrMatcher cidrMatcher;

    /**
     * 创建 IP 解析器。
     */
    public ClientIpResolver(CidrMatcher cidrMatcher) {
        this.cidrMatcher = cidrMatcher;
    }

    /**
     * 解析真实客户端 IP。
     */
    public String resolve(ChannelHandlerContext ctx, HttpHeaders headers,
            List<String> trustedProxies, Integer trustedProxyHops) {
        String remoteIp = resolveRemoteIp(ctx);
        if (remoteIp == null) {
            return "unknown";
        }

        List<String> forwardedChain = resolveForwardedChain(headers);
        if (forwardedChain.isEmpty()) {
            return remoteIp;
        }

        if (trustedProxyHops != null) {
            return resolveByTrustedHops(remoteIp, trustedProxies,
                    forwardedChain, trustedProxyHops);
        }
        return resolveByTrustedCidrs(remoteIp, trustedProxies, forwardedChain);
    }

    private String resolveByTrustedHops(String remoteIp, List<String> trustedProxies,
            List<String> forwardedChain, int trustedProxyHops) {
        if (trustedProxyHops < 0 || forwardedChain.isEmpty()) {
            return remoteIp;
        }

        // 当配置了 trustedProxies 时，仍校验 remoteAddress 属于可信代理；
        // 仅配置 trustedProxyHops 时采用固定跳数模式。
        if (trustedProxies != null && !trustedProxies.isEmpty()
                && !isTrustedProxy(remoteIp, trustedProxies)) {
            return remoteIp;
        }

        int targetIndex = forwardedChain.size() - 1 - trustedProxyHops;
        if (targetIndex < 0 || targetIndex >= forwardedChain.size()) {
            return remoteIp;
        }
        return forwardedChain.get(targetIndex);
    }

    private String resolveByTrustedCidrs(String remoteIp, List<String> trustedProxies,
            List<String> forwardedChain) {
        if (!isTrustedProxy(remoteIp, trustedProxies)) {
            return remoteIp;
        }

        for (int i = forwardedChain.size() - 1; i >= 0; i--) {
            String candidate = forwardedChain.get(i);
            if (!isTrustedProxy(candidate, trustedProxies)) {
                return candidate;
            }
        }
        return forwardedChain.get(0);
    }

    private static List<String> resolveForwardedChain(HttpHeaders headers) {
        String xff = headers.get("X-Forwarded-For");
        List<String> chain = parseXff(xff);
        if (!chain.isEmpty()) {
            return chain;
        }

        String forwarded = headers.get("Forwarded");
        return parseForwarded(forwarded);
    }

    private static List<String> parseXff(String xff) {
        if (xff == null || xff.isBlank()) {
            return List.of();
        }
        String[] segments = xff.split(",");
        List<String> chain = new ArrayList<>(segments.length);
        for (String segment : segments) {
            String normalized = normalizeIpToken(segment);
            if (normalized != null) {
                chain.add(normalized);
            }
        }
        return chain;
    }

    private boolean isTrustedProxy(String remoteIp, List<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (String rule : trustedProxies) {
            if (cidrMatcher.matches(remoteIp, rule)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveRemoteIp(ChannelHandlerContext ctx) {
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress.getAddress().getHostAddress();
        }
        return remote == null ? null : remote.toString();
    }

    private static List<String> parseForwarded(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) {
            return List.of();
        }

        // RFC 7239 允许多个转发节点以逗号分隔，每个节点内用分号分隔参数。
        String[] entries = forwarded.split(",");
        List<String> chain = new ArrayList<>(entries.length);
        for (String entry : entries) {
            String[] pairs = entry.split(";");
            for (String pair : pairs) {
                String trimmed = pair.trim();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith("for=")) {
                    continue;
                }
                String value = trimmed.substring(4).trim();
                String normalized = normalizeIpToken(value);
                if (normalized != null) {
                    chain.add(normalized);
                }
                break;
            }
        }
        return chain;
    }

    private static String normalizeIpToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.isEmpty()) {
            return null;
        }

        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty()) {
            return null;
        }
        if ("unknown".equalsIgnoreCase(value) || value.startsWith("_")) {
            return null;
        }

        if (value.startsWith("[")) {
            int closingBracket = value.indexOf(']');
            if (closingBracket <= 1) {
                return null;
            }
            String ipv6 = value.substring(1, closingBracket);
            return NetUtil.isValidIpV6Address(ipv6) ? ipv6 : null;
        }

        String withoutPort = stripIpv4Port(value);
        if (NetUtil.isValidIpV4Address(withoutPort)) {
            return withoutPort;
        }

        String withoutZone = stripIpv6ZoneId(value);
        if (NetUtil.isValidIpV6Address(withoutZone)) {
            return withoutZone;
        }
        return null;
    }

    private static String stripIpv4Port(String value) {
        int colonIdx = value.lastIndexOf(':');
        if (colonIdx <= 0) {
            return value;
        }
        if (value.indexOf(':') != colonIdx) {
            return value;
        }
        String hostPart = value.substring(0, colonIdx);
        String portPart = value.substring(colonIdx + 1);
        if (portPart.chars().allMatch(Character::isDigit)) {
            return hostPart;
        }
        return value;
    }

    private static String stripIpv6ZoneId(String value) {
        int zoneIdx = value.indexOf('%');
        if (zoneIdx <= 0) {
            return value;
        }
        return value.substring(0, zoneIdx);
    }
}
