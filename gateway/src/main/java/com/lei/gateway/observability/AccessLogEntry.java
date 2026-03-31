package com.lei.gateway.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 结构化访问日志条目，记录每个代理请求的关键信息。
 *
 * <p>参考 Envoy access log 模式，以 JSON 格式输出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessLogEntry {

    @JsonProperty("method")
    private String method;

    @JsonProperty("path")
    private String path;

    @JsonProperty("statusCode")
    private int statusCode;

    @JsonProperty("durationMs")
    private long durationMs;

    @JsonProperty("clientIp")
    private String clientIp;

    @JsonProperty("upstream")
    private String upstream;

    @JsonProperty("requestBodySize")
    private long requestBodySize;

    @JsonProperty("responseBodySize")
    private long responseBodySize;

    @JsonProperty("traceId")
    private String traceId;

    @JsonProperty("authRequired")
    private Boolean authRequired;

    @JsonProperty("authPassed")
    private Boolean authPassed;

    @JsonProperty("securityDecision")
    private String securityDecision;

    @JsonProperty("securityFilter")
    private String securityFilter;

    @JsonProperty("securityReason")
    private String securityReason;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public long getRequestBodySize() {
        return requestBodySize;
    }

    public void setRequestBodySize(long requestBodySize) {
        this.requestBodySize = requestBodySize;
    }

    public long getResponseBodySize() {
        return responseBodySize;
    }

    public void setResponseBodySize(long responseBodySize) {
        this.responseBodySize = responseBodySize;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Boolean getAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(Boolean authRequired) {
        this.authRequired = authRequired;
    }

    public Boolean getAuthPassed() {
        return authPassed;
    }

    public void setAuthPassed(Boolean authPassed) {
        this.authPassed = authPassed;
    }

    public String getSecurityDecision() {
        return securityDecision;
    }

    public void setSecurityDecision(String securityDecision) {
        this.securityDecision = securityDecision;
    }

    public String getSecurityFilter() {
        return securityFilter;
    }

    public void setSecurityFilter(String securityFilter) {
        this.securityFilter = securityFilter;
    }

    public String getSecurityReason() {
        return securityReason;
    }

    public void setSecurityReason(String securityReason) {
        this.securityReason = securityReason;
    }
}
