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
package com.lei.java.gateway.server.session;

import java.util.Collection;

import io.netty.channel.Channel;

/**
 * 会话管理器接口
 */
public interface SessionManager {
    /**
     * 创建会话
     */
    Session createSession(String clientId, Channel channel);

    /**
     * 根据会话ID获取会话
     */
    Session getSession(String sessionId);

    /**
     * 根据客户端ID获取会话
     */
    Session getSessionByClientId(String clientId);

    /**
     * 移除会话
     */
    void removeSession(String sessionId);

    /**
     * 获取所有会话
     */
    Collection<Session> getAllSessions();

    /**
     * 获取会话数量
     */
    int getSessionCount();

    /**
     * 判断会话是否存在
     */
    boolean existSession(String sessionId);

    /**
     * 更新最后活跃时间
     */
    void updateLastActiveTime(String sessionId);

    /**
     * 关闭会话管理器
     */
    void shutdown();

    /**
     * 获取活跃会话数量
     *
     * @return 数量
     */
    long getActiveSessionCount();
}