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

import org.junit.jupiter.api.AfterAll;

/**
 * <p>
 * 基础的集成测试
 * </p>
 *
 * @author 伍磊
 */
public abstract class BaseIntegrationTest extends CommonMicroServiceTest {

    public static final NacosTestContainer NACOS_CONTAINER = NacosTestContainer.getInstance();

    @AfterAll
    static void tearDownAll() {
        // ...
        // 如果容器对象存在但没有在运行，说明它在中途崩溃了
        if (NACOS_CONTAINER != null && !NACOS_CONTAINER.isRunning()) {
            System.err.println("!!!!!!!!!! NACOS CONTAINER CRASHED !!!!!!!!!!");
            System.err.println("=== DUMPING LOGS FROM CRASHED CONTAINER: ===");
            // 打印容器生命周期内的所有标准输出和错误输出
            System.err.println(NACOS_CONTAINER.getLogs());
            System.err.println("============================================");
        } else if (NACOS_CONTAINER != null) {
            NACOS_CONTAINER.stop();
        }
        // ...
    }
}
