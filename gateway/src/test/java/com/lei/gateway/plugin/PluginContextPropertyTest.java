package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.Route;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.mockito.Mockito;

/**
 * Feature: plugin-system, Property 4: PluginContext 属性在插件间共享。
 *
 * <p>验证 setAttribute/getAttribute 往返一致性，以及 clientIp/userId 一等公民字段的 setter/getter 往返。
 */
class PluginContextPropertyTest {

    private PluginContext createContext() {
        return new PluginContext(
                Mockito.mock(ChannelHandlerContext.class),
                Mockito.mock(HttpRequest.class),
                new Route(),
                "trace-prop");
    }

    /**
     * 任意 String 属性经 setAttribute 后，getAttribute 应返回相同值。
     */
    @Property(tries = 100)
    void stringAttributeRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String key,
            @ForAll String value) {
        PluginContext ctx = createContext();
        ctx.setAttribute(key, value);

        assertThat(ctx.getAttribute(key, String.class)).isEqualTo(value);
    }

    /**
     * 任意 Integer 属性经 setAttribute 后，getAttribute 应返回相同值。
     */
    @Property(tries = 100)
    void integerAttributeRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String key,
            @ForAll Integer value) {
        PluginContext ctx = createContext();
        ctx.setAttribute(key, value);

        assertThat(ctx.getAttribute(key, Integer.class)).isEqualTo(value);
    }

    /**
     * 任意 Boolean 属性经 setAttribute 后，getAttribute 应返回相同值。
     */
    @Property(tries = 100)
    void booleanAttributeRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String key,
            @ForAll Boolean value) {
        PluginContext ctx = createContext();
        ctx.setAttribute(key, value);

        assertThat(ctx.getAttribute(key, Boolean.class)).isEqualTo(value);
    }

    /**
     * 模拟插件 A 设置属性后，插件 B 在同一 PluginContext 上能读取到相同值。
     */
    @Property(tries = 100)
    void attributeSharedBetweenPlugins(
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String key,
            @ForAll String value) {
        PluginContext ctx = createContext();

        // 插件 A 写入
        Plugin pluginA = stubPlugin("plugin-a", 100);
        simulatePluginWrite(pluginA, ctx, key, value);

        // 插件 B 读取
        Plugin pluginB = stubPlugin("plugin-b", 200);
        String read = simulatePluginRead(pluginB, ctx, key, String.class);

        assertThat(read).isEqualTo(value);
    }

    /**
     * 多个插件依次写入不同属性，所有属性均可读取。
     */
    @Property(tries = 100)
    void multiplePluginsWriteDistinctAttributes(
            @ForAll List<@AlphaChars @StringLength(min = 2, max = 20) String> keys) {
        PluginContext ctx = createContext();

        for (int ii = 0; ii < keys.size(); ii++) {
            String uniqueKey = keys.get(ii) + ii;
            ctx.setAttribute(uniqueKey, ii);
        }

        for (int ii = 0; ii < keys.size(); ii++) {
            String uniqueKey = keys.get(ii) + ii;
            assertThat(ctx.getAttribute(uniqueKey, Integer.class)).isEqualTo(ii);
        }
    }

    /**
     * clientIp setter/getter 往返一致性。
     */
    @Property(tries = 100)
    void clientIpRoundTrip(@ForAll @StringLength(min = 7, max = 45) String ip) {
        PluginContext ctx = createContext();
        ctx.setClientIp(ip);

        assertThat(ctx.getClientIp()).isEqualTo(ip);
    }

    /**
     * userId setter/getter 往返一致性。
     */
    @Property(tries = 100)
    void userIdRoundTrip(@ForAll String userId) {
        PluginContext ctx = createContext();
        ctx.setUserId(userId);

        assertThat(ctx.getUserId()).isEqualTo(userId);
    }

    /**
     * 插件 A 设置 clientIp，插件 B 能读取到相同值。
     */
    @Property(tries = 100)
    void clientIpSharedBetweenPlugins(@ForAll @StringLength(min = 7, max = 45) String ip) {
        PluginContext ctx = createContext();

        // 插件 A（如 RealIpPlugin）设置 clientIp
        ctx.setClientIp(ip);

        // 插件 B（如 IpAccessPlugin）读取 clientIp
        assertThat(ctx.getClientIp()).isEqualTo(ip);
    }

    /**
     * 插件 A 设置 userId，插件 B 能读取到相同值。
     */
    @Property(tries = 100)
    void userIdSharedBetweenPlugins(@ForAll String userId) {
        PluginContext ctx = createContext();

        // 插件 A（如 AuthPlugin）设置 userId
        ctx.setUserId(userId);

        // 插件 B（如 UserRateLimitPlugin）读取 userId
        assertThat(ctx.getUserId()).isEqualTo(userId);
    }

    /**
     * 后写入的同名属性覆盖先前值。
     */
    @Property(tries = 100)
    void laterWriteOverridesPrevious(
            @ForAll @AlphaChars @StringLength(min = 2, max = 50) String key,
            @ForAll String first,
            @ForAll String second) {
        PluginContext ctx = createContext();
        ctx.setAttribute(key, first);
        ctx.setAttribute(key, second);

        assertThat(ctx.getAttribute(key, String.class)).isEqualTo(second);
    }

    private Plugin stubPlugin(String name, int priority) {
        return new Plugin() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public PluginPhase phase() {
                return PluginPhase.REQUEST;
            }

            @Override
            public int defaultPriority() {
                return priority;
            }

            @Override
            public PluginResult execute(PluginContext context, PluginConfig config) {
                return PluginResult.doContinue();
            }
        };
    }

    private void simulatePluginWrite(Plugin plugin, PluginContext ctx,
            String key, Object value) {
        ctx.setAttribute(key, value);
    }

    private <T> T simulatePluginRead(Plugin plugin, PluginContext ctx,
            String key, Class<T> type) {
        return ctx.getAttribute(key, type);
    }
}
