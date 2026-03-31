package com.lei.gateway.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP / CIDR 匹配器。
 */
public class CidrMatcher {

    private final Map<String, ParsedCidr> cache = new ConcurrentHashMap<>();

    /**
     * 判断 IP 是否命中规则。
     */
    public boolean matches(String ip, String cidrOrIp) {
        if (ip == null || ip.isBlank() || cidrOrIp == null || cidrOrIp.isBlank()) {
            return false;
        }
        ParsedCidr parsed = cache.computeIfAbsent(cidrOrIp.trim(), this::parse);
        if (parsed == null) {
            return false;
        }
        try {
            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            return parsed.matches(ipBytes);
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private ParsedCidr parse(String source) {
        String value = source.trim();
        try {
            if (!value.contains("/")) {
                byte[] single = InetAddress.getByName(value).getAddress();
                return new ParsedCidr(single, single.length * 8);
            }
            String[] parts = value.split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > network.length * 8) {
                return null;
            }
            return new ParsedCidr(network, prefix);
        } catch (Exception ex) {
            return null;
        }
    }

    private static final class ParsedCidr {

        private final byte[] network;
        private final int prefixLength;

        private ParsedCidr(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        private boolean matches(byte[] ip) {
            if (ip.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (ip[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
