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
package com.lei.java.gateway.server.route;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.nacos.NacosConfig;
import com.lei.java.gateway.server.config.nacos.NacosConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;

class NacosConfigLoaderTests {

    @Test
    void testLoadDefaultConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos.properties");

        assertThat(config).isNotNull();
        assertThat(config.getServerAddr()).isNotNull();
        assertThat(config.getNamespace()).isEqualTo("public");
        assertThat(config.getGroup()).isEqualTo("DEFAULT_GROUP");
    }

    @Test
    void testLoadCustomConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");

        assertThat(config).isNotNull();
        assertThat(config.getServerAddr()).isNotNull();
        assertThat(config.getNamespace()).isEqualTo("public");
        assertThat(config.getGroup()).isEqualTo("TEST_GROUP");
    }
}