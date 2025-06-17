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
package com.lei.java.gateway.server.base;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public final class NacosTestContainer extends GenericContainer<NacosTestContainer> {
    private static final String NACOS_VERSION = "v2.5.1";
    // nacos 要求 http 端口与 grpc 端口相差 1000，所以这里需要映射下
    private static final int NACOS_PORT = 8848;
    private static final int HOST_PORT = 18848;
    private static final int NACOS_GRPC_PORT = 9848;
    private static final int HOST_GRPC_PORT = 19848;

    private static final NacosTestContainer INSTANCE;

    static {
        INSTANCE = new NacosTestContainer();
        INSTANCE.start();
    }

    private NacosTestContainer() {
        super(DockerImageName.parse("nacos/nacos-server:"
                + NACOS_VERSION));

        // ✅ 添加这一行，告诉 Nacos Server 它对外服务的 IP 是宿主机的 IP
        // withEnv("NACOS_SERVER_IP", "host.testcontainers.internal");
        addFixedExposedPort(HOST_PORT, NACOS_PORT);
        addFixedExposedPort(HOST_GRPC_PORT, NACOS_GRPC_PORT);
        withEnv("MODE", "standalone");
        withEnv("NACOS_AUTH_ENABLE", "false");
        withEnv("JVM_XMS", "256m");
        withEnv("JVM_XMX", "256m");

        // 设置更长的启动超时时间
        withStartupTimeout(Duration.ofMinutes(5));

        // 使用更可靠的健康检查
        waitingFor(Wait.forLogMessage(".*Nacos started successfully.*", 1)
                .withStartupTimeout(Duration.ofMinutes(5)));
    }

    public static NacosTestContainer getInstance() {
        return INSTANCE;
    }

    public String getServerAddr() {
        return getHost()
                + ":"
                + getMappedPort(NACOS_PORT);
    }

    public String getGrpcServerAddr() {
        return getHost()
                + ":"
                + getMappedPort(NACOS_GRPC_PORT);
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
            System.setProperty("nacos.server.addr", getServerAddr());
            System.setProperty("nacos.server.grpc.addr", getGrpcServerAddr());
        }
    }

    @Override
    public void stop() {
        // 不实现 stop 方法，让容器一直运行
    }
}