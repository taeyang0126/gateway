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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.Channel;

/**
 * 默认会话管理器实现
 */
public class DefaultSessionManager implements SessionManager {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> clientIdIndex = new ConcurrentHashMap<>();

    @Override
    public Session createSession(String clientId, Channel channel) {
        // 如果已存在，先关闭旧的会话
        Session oldSession = clientIdIndex.get(clientId);
        if (oldSession != null) {
            removeSession(oldSession.getId());
        }

        // 创建新会话
        String sessionId = generateSessionId();
        DefaultSession session = new DefaultSession(sessionId, clientId, channel);
        sessions.put(sessionId, session);
        clientIdIndex.put(clientId, session);
        return session;
    }

    @Override
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public Session getSessionByClientId(String clientId) {
        return clientIdIndex.get(clientId);
    }

    @Override
    public void removeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session != null) {
            clientIdIndex.remove(session.getClientId());
            session.close();
        }
    }

    @Override
    public Collection<Session> getAllSessions() {
        return sessions.values();
    }

    @Override
    public int getSessionCount() {
        return sessions.size();
    }

    @Override
    public boolean existSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    private String generateSessionId() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    /**
     * 关闭会话管理器
     */
    public void shutdown() {
        // 关闭所有会话
        sessions.values()
                .forEach(Session::close);
        sessions.clear();
        clientIdIndex.clear();
    }
}