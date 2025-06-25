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
import org.testcontainers.utility.DockerImageName;

/**
 * <p>
 * RedisTestContainer
 * </p>
 *
 * @author 伍磊
 */
public final class RedisTestContainer extends GenericContainer<RedisTestContainer> {
    private static final String REDIS_VERSION = "7.4.2-alpine";
    private static final int REDIS_PORT = 6379;

    private static final RedisTestContainer INSTANCE;

    static {
        INSTANCE = new RedisTestContainer();
        INSTANCE.start();
    }

    private RedisTestContainer() {
        super(DockerImageName.parse("redis:"
                + REDIS_VERSION));

        addExposedPort(REDIS_PORT);

        withStartupTimeout(Duration.ofMinutes(2));
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
        }
    }

    public String getServerAddr() {
        return getHost()
                + ":"
                + getMappedPort(REDIS_PORT);
    }

    @Override
    public void stop() {
        // 不实现 stop 方法，让容器一直运行
    }

    public static RedisTestContainer getInstance() {
        return INSTANCE;
    }
}
