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
    private static final String CLIENT_ID = "gateway-client-example";

    public static void main(String[] args) throws Exception {
        // 创建Spring容器
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 设置扫描包路径
        context.register(UpstreamServerConfiguration.class);
        // 刷新容器
        context.refresh();

        // 推送消息
        MessageDispatcher messageDispatcher = context.getBean(MessageDispatcher.class);
        messageDispatcher.dispatch(CLIENT_ID, "Hello World".getBytes());

        System.in.read();
    }
}
