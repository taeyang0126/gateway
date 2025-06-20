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

import java.time.Duration;

import io.netty.channel.Channel;
import org.redisson.api.RBatch;
import org.redisson.api.RMapAsync;
import org.redisson.api.RedissonClient;

import com.lei.java.gateway.server.config.GlobalNodeId;
import com.lei.java.gateway.server.constants.CacheConstant;

import static com.lei.java.gateway.client.constants.GatewayConstant.LAST_ACTIVE_TIME;
import static com.lei.java.gateway.client.constants.GatewayConstant.NODE;
import static com.lei.java.gateway.client.constants.GatewayConstant.SESSION_EXPIRE_SECONDS;
import static com.lei.java.gateway.client.constants.GatewayConstant.SESSION_ID;

/**
 * <p>
 * 分布式 session 管理器
 * </p>
 *
 * @author 伍磊
 */
public class DistributedSessionManager extends LocalSessionManager {
    private final RedissonClient redissonClient;

    public DistributedSessionManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public Session createSession(String clientId, Channel channel) {
        Session session = super.createSession(clientId, channel);
        saveCache(session);

        return session;
    }

    @Override
    public void removeSession(String sessionId) {
        Session session = super.getSession(sessionId);
        if (session != null) {
            String cacheKey = String.format(CacheConstant.SESSION_KEY, session.getClientId());
            redissonClient.getMap(cacheKey)
                    .remove(cacheKey);
        }
        super.removeSession(sessionId);
    }

    @Override
    public void updateLastActiveTime(String sessionId) {
        super.updateLastActiveTime(sessionId);
        Session session = super.getSession(sessionId);
        if (session != null) {
            saveCache(session);
        }
    }

    private void saveCache(Session session) {
        String cacheKey = String.format(CacheConstant.SESSION_KEY, session.getClientId());

        RBatch batch = redissonClient.createBatch();
        RMapAsync<String, Object> map = batch.getMap(cacheKey);
        map.putAsync(NODE, GlobalNodeId.getNodeId());
        map.putAsync(SESSION_ID, session.getId());
        map.putAsync(LAST_ACTIVE_TIME, session.getLastActiveTime());
        map.expireAsync(Duration.ofSeconds(SESSION_EXPIRE_SECONDS));

        batch.execute();
    }
}
