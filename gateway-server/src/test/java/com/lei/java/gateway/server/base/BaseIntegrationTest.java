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

/**
 * <p>
 * 基础的集成测试
 * </p>
 *
 * @author 伍磊
 */
public abstract class BaseIntegrationTest extends CommonMicroServiceTest {

    public static final NacosTestContainer NACOS_CONTAINER = NacosTestContainer.getInstance();
    public static final RedisTestContainer REDIS_CONTAINER = RedisTestContainer.getInstance();
}
