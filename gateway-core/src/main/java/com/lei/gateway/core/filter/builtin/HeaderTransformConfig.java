package com.lei.gateway.core.filter.builtin;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求/响应头改写过滤器配置。
 */
public class HeaderTransformConfig {

    private HeaderRules request = new HeaderRules();
    private HeaderRules response = new HeaderRules();

    public HeaderRules getRequest() {
        return request;
    }

    public void setRequest(HeaderRules request) {
        this.request = request;
    }

    public HeaderRules getResponse() {
        return response;
    }

    public void setResponse(HeaderRules response) {
        this.response = response;
    }

    /** 请求头或响应头改写规则集合。 */
    public static class HeaderRules {

        private List<HeaderEntry> add = new ArrayList<>();
        private List<HeaderEntry> set = new ArrayList<>();
        private List<String> remove = new ArrayList<>();

        public List<HeaderEntry> getAdd() {
            return add;
        }

        public void setAdd(List<HeaderEntry> add) {
            this.add = add;
        }

        public List<HeaderEntry> getSet() {
            return set;
        }

        public void setSet(List<HeaderEntry> set) {
            this.set = set;
        }

        public List<String> getRemove() {
            return remove;
        }

        public void setRemove(List<String> remove) {
            this.remove = remove;
        }
    }

    /** 单条头改写规则（name/value 键值对）。 */
    public static class HeaderEntry {

        private String name;
        private String value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
