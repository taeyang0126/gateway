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
package com.lei.java.gateway.server.gatling;

import java.time.Duration;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * <p>
 * Http 压测示例
 * </p>
 *
 * @author 伍磊
 */
public class HttpSimulation extends Simulation {

    // 1. HTTP 协议配置
    HttpProtocolBuilder httpProtocol = http.baseUrl("http://127.0.0.1:8081") // 设置目标服务器的基础URL
            .acceptHeader("application/json"); // 设置通用请求头

    FeederBuilder<String> csvFeeder = csv("http-delay.csv").random();

    // 2. 场景定义
    ScenarioBuilder basicScenario = scenario("delay") // 场景命名
            // .feed(csvFeeder)
            .exec(http("delay") // 本次请求的命名，会显示在报告中
                    // .get("/delay/#{delay}") // 请求的路径 #{delay}表示根据取csv中的delay列
                    .get("/delay/0.1") // 请求的路径
                    .check(status().is(200)) // 检查HTTP响应状态码是否为200
            );

    // 3. 注入策略
    public HttpSimulation() {
        /*
         * this.setUp( basicScenario.injectOpen( // 负载模型：在10秒内，平滑地将并发用户数从1增加到20 //
         * 20表示20个虚拟用户，一个虚拟用户会完成一个场景 // rampUsers(20).during(10)
         *
         * // 每秒恒定注入100个新用户，持续2分钟 // constantUsersPerSec(100).during(Duration.ofMinutes(2))
         *
         * // 瞬间注入1000用户，检测极限压力情况 // atOnceUsers(1000)
         *
         * // 3分钟之内从每秒1500增加到每秒2500（定位QPS瓶颈） //
         * rampUsersPerSec(1500).to(2500).during(Duration.ofMinutes(3)))
         *
         * // 恒定1800qps持续3min constantUsersPerSec(1800).during(Duration.ofMinutes(3)))
         *
         * ).protocols(httpProtocol);
         */

        // 闭合模型
        this.setUp(basicScenario.injectClosed(
                // 3分钟内保持1600个用户
                constantConcurrentUsers(1600).during(Duration.ofMinutes(3))))
                .protocols(httpProtocol);
    }
}
