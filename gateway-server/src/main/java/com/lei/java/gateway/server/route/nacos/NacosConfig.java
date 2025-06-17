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
package com.lei.java.gateway.server.route.nacos;

import java.util.Properties;

import com.alibaba.nacos.api.PropertyKeyConst;

public class NacosConfig {
    private String serverAddr;
    private String namespace;
    private String username;
    private String password;
    private String group = "DEFAULT_GROUP";

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

    // Getters and Setters
    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

}