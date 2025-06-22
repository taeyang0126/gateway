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

import java.util.Properties;

import com.alibaba.nacos.api.PropertyKeyConst;
import lombok.Data;

@Data
public class NacosConfig {
    private String serverAddr;
    private String namespace;
    private String username;
    private String password;
    private String group;
    private String dataId;

    public NacosConfig(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public NacosConfig(String serverAddr, String namespace) {
        this.serverAddr = serverAddr;
        this.namespace = namespace;
    }

    public Properties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        if (namespace != null) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (username != null) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (password != null) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return properties;
    }
}