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
package com.lei.java.gateway.upstream.server.spring.example;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.lei.java.gateway.sdk.core.message.MessageDispatcher;

/**
 * <p>
 * UpstreamServer
 * </p>
 *
 * @author 伍磊
 */
public class UpstreamServer {
    private static Logger logger = LoggerFactory.getLogger(UpstreamServer.class);
    private static final String CLIENT_ID = "gateway-client-example";

    public static void main(String[] args) throws Exception {
        // 创建Spring容器
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 设置扫描包路径
        context.register(UpstreamServerConfiguration.class);
        // 刷新容器
        context.refresh();
        final ExecutorService executorService = Executors.newFixedThreadPool(10);
        final Random random = new Random();

        // 推送消息
        MessageDispatcher messageDispatcher = context.getBean(MessageDispatcher.class);
        int count = 1000;
        CountDownLatch countDownLatch = new CountDownLatch(count);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String msg = "Hello World -> "
                    + i;
            int finalI = i;
            executorService.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(random.nextInt(1000, 3000));
                } catch (InterruptedException ignore) {
                }
                messageDispatcher.dispatch(start + finalI, CLIENT_ID, msg.getBytes())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                logger.error("msg push error: ", ex);
                            } else {
                                logger.info("{} msg push success", msg);
                            }
                            countDownLatch.countDown();
                        });
            });
        }

        countDownLatch.await();
        logger.info("Upstream Server push {} msg in {} ms",
                count,
                System.currentTimeMillis() - start);
        context.close();
        executorService.shutdown();
    }
}
