/*
 * Copyright (c) 2025 The gateway Project
 * https://github.com/taeyang0126/gateway
 *
 * Licensed under the MIT License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.common.config.nacos;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos配置加载工具类
 */
public class NacosConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(NacosConfigLoader.class);

    // 配置文件相关
    private static final String DEFAULT_CONFIG_FILE = "nacos.properties";
    private static final String PREFIX = "nacos.";

    // 配置项Key
    private static final String KEY_SERVER_ADDR = "server-addr";
    private static final String KEY_NAMESPACE = "namespace";
    private static final String KEY_GROUP = "group";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_DATA_ID = "data-id";

    // 默认值
    private static final String DEFAULT_NAMESPACE = "public";
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 从默认配置文件加载配置
     */
    public static NacosConfig load() {
        return load(DEFAULT_CONFIG_FILE);
    }

    /**
     * 从指定配置文件加载配置
     *
     * @param configFile 配置文件名（相对于 classpath）
     */
    public static NacosConfig load(String configFile) {
        Properties props = new Properties();
        try (InputStream in = NacosConfigLoader.class.getClassLoader()
                .getResourceAsStream(configFile)) {
            if (in == null) {
                throw new IllegalArgumentException(
                        "配置文件不存在: "
                                + configFile);
            }
            props.load(in);
            return buildConfig(props);
        } catch (IOException e) {
            logger.error("加载Nacos配置文件失败: {}", configFile, e);
            throw new RuntimeException("Failed to load nacos config", e);
        }
    }

    /**
     * 从Properties对象构建NacosConfig
     */
    private static NacosConfig buildConfig(Properties props) {
        String serverAddr = resolveValue(getRequired(props, KEY_SERVER_ADDR));
        String namespace = resolveValue(get(props, KEY_NAMESPACE, DEFAULT_NAMESPACE));

        NacosConfig config = new NacosConfig(serverAddr, namespace);

        // 设置可选配置
        config.setGroup(resolveValue(get(props, KEY_GROUP, DEFAULT_GROUP)));
        config.setUsername(resolveValue(get(props, KEY_USERNAME, null)));
        config.setPassword(resolveValue(get(props, KEY_PASSWORD, null)));
        config.setDataId(resolveValue(get(props, KEY_DATA_ID, null)));

        logger.info("已加载Nacos配置: serverAddr={}, namespace={}, group={}, dataId={}",
                serverAddr,
                namespace,
                config.getGroup(),
                config.getDataId());

        return config;
    }

    private static String getRequired(Properties props, String key) {
        String value = get(props, key, null);
        if (value == null
                || value.trim()
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    "必需的配置项未设置: "
                            + PREFIX
                            + key);
        }
        return value;
    }

    private static String get(Properties props, String key, String defaultValue) {
        return props.getProperty(PREFIX + key, defaultValue);
    }

    /**
     * 解析配置值，支持系统属性替换 例如: ${user.home} 将被替换为系统属性 user.home 的值
     */
    private static String resolveValue(String value) {
        if (value == null) {
            return null;
        }

        // 检查是否包含 ${} 占位符
        if (!value.contains("${")) {
            return value;
        }

        // 解析所有的占位符
        String result = value;
        int startIndex;
        while ((startIndex = result.indexOf("${")) != -1) {
            int endIndex = result.indexOf("}", startIndex);
            if (endIndex == -1) {
                break;
            }

            String placeholder = result.substring(startIndex + 2, endIndex);
            String propValue = System.getProperty(placeholder);

            if (propValue != null) {
                result = result.substring(0, startIndex) + propValue
                        + result.substring(endIndex + 1);
            } else {
                logger.warn("系统属性 {} 未定义", placeholder);
                // 如果找不到系统属性，保留原值
                break;
            }
        }

        return result;
    }
}