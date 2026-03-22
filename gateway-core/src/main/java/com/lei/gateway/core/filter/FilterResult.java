package com.lei.gateway.core.filter;

/**
 * 过滤器前置处理结果。
 */
public enum FilterResult {
    /** 继续执行下一个过滤器或转发请求。 */
    CONTINUE,
    /** 终止过滤器链，已直接写出错误响应。 */
    ABORT
}
