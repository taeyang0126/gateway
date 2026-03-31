package com.lei.gateway.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.config.PluginConfigEntry;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PluginPhase、PluginResultType、PluginResult、PluginConfigEntry 单元测试。
 */
class PluginBasicTypesTest {

    // ── PluginPhase ──

    @Test
    void pluginPhase_hasExactlyFourValues() {
        assertThat(PluginPhase.values()).containsExactly(
                PluginPhase.REQUEST, PluginPhase.PROXY, PluginPhase.RESPONSE, PluginPhase.ERROR);
    }

    // ── PluginResultType ──

    @Test
    void pluginResultType_hasExactlyThreeValues() {
        assertThat(PluginResultType.values()).containsExactly(
                PluginResultType.CONTINUE, PluginResultType.SHORT_CIRCUIT, PluginResultType.ERROR);
    }

    // ── PluginResult.doContinue ──

    @Test
    void doContinue_returnsContinueType() {
        PluginResult result = PluginResult.doContinue();

        assertThat(result.getType()).isEqualTo(PluginResultType.CONTINUE);
        assertThat(result.isContinue()).isTrue();
        assertThat(result.getStatus()).isNull();
        assertThat(result.getBody()).isNull();
        assertThat(result.getPluginName()).isNull();
        assertThat(result.getReason()).isNull();
        assertThat(result.getRetryAfterSeconds()).isNull();
    }

    // ── PluginResult.shortCircuit ──

    @Test
    void shortCircuit_carriesAllFields() {
        PluginResult result = PluginResult.shortCircuit(
                HttpResponseStatus.FORBIDDEN, "denied", "ip-access", "ip blocked", null);

        assertThat(result.getType()).isEqualTo(PluginResultType.SHORT_CIRCUIT);
        assertThat(result.isContinue()).isFalse();
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        assertThat(result.getBody()).isEqualTo("denied");
        assertThat(result.getPluginName()).isEqualTo("ip-access");
        assertThat(result.getReason()).isEqualTo("ip blocked");
        assertThat(result.getRetryAfterSeconds()).isNull();
    }

    @Test
    void shortCircuit_withRetryAfterSeconds() {
        PluginResult result = PluginResult.shortCircuit(
                HttpResponseStatus.TOO_MANY_REQUESTS, "rate limited",
                "ip-rate-limit", "exceeded", 30);

        assertThat(result.getRetryAfterSeconds()).isEqualTo(30);
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS);
    }

    // ── PluginResult.error ──

    @Test
    void error_returnsErrorType() {
        PluginResult result = PluginResult.error(
                HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal plugin error");

        assertThat(result.getType()).isEqualTo(PluginResultType.ERROR);
        assertThat(result.isContinue()).isFalse();
        assertThat(result.getStatus()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isEqualTo("Internal plugin error");
        assertThat(result.getPluginName()).isNull();
        assertThat(result.getReason()).isNull();
        assertThat(result.getRetryAfterSeconds()).isNull();
    }

    // ── PluginConfigEntry ──

    @Test
    void pluginConfigEntry_enabledDefaultsToTrue() {
        PluginConfigEntry entry = new PluginConfigEntry();

        assertThat(entry.getEnabled()).isTrue();
        assertThat(entry.getName()).isNull();
        assertThat(entry.getPriority()).isNull();
        assertThat(entry.getConfig()).isNull();
    }

    @Test
    void pluginConfigEntry_settersAndGetters() {
        PluginConfigEntry entry = new PluginConfigEntry();
        entry.setName("auth");
        entry.setEnabled(false);
        entry.setPriority(4000);
        entry.setConfig(Map.of("type", "JWT"));

        assertThat(entry.getName()).isEqualTo("auth");
        assertThat(entry.getEnabled()).isFalse();
        assertThat(entry.getPriority()).isEqualTo(4000);
        assertThat(entry.getConfig()).containsEntry("type", "JWT");
    }
}
