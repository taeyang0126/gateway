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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;

import com.lei.java.gateway.common.constants.CacheConstant;
import com.lei.java.gateway.common.constants.GatewayConstant;
import com.lei.java.gateway.server.base.BaseIntegrationTest;
import com.lei.java.gateway.server.config.GlobalNodeId;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * <p>
 * 分布式 session 测试
 * </p>
 *
 * @author 伍磊
 */
@ExtendWith(MockitoExtension.class)
public class DistributedSessionManagerIT extends BaseIntegrationTest {
    private static RedissonClient redissonClient;
    private static SessionManager sessionManager;
    @Spy
    private Channel channel = new NioSocketChannel();

    @BeforeAll
    public static void init() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://"
                        + REDIS_CONTAINER.getServerAddr())
                .setDatabase(10)
                .setConnectionPoolSize(1)
                .setConnectionMinimumIdleSize(1)
                .setConnectTimeout(3000);

        config.setCodec(new JsonJacksonCodec());

        redissonClient = Redisson.create(config);
        sessionManager = new DistributedSessionManager(redissonClient);
    }

    @Test
    public void test_redisson() {
        String key = UUID.randomUUID()
                .toString();
        String value = UUID.randomUUID()
                .toString();
        RBucket<String> bucket = redissonClient.getBucket(key);
        assertThat(bucket).isNotNull();
        assertThat(bucket.get()).isNull();
        bucket.set(value);
        assertThat(bucket.get()).isNotNull()
                .isEqualTo(value);
    }

    @Test
    public void test_createSession() {
        String clientId = UUID.randomUUID()
                .toString();
        Session sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNull();

        sessionManager.createSession(clientId, channel);
        sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNotNull()
                .matches(session -> session.getClientId()
                        .equals(clientId));
        String sessionId = sessionByClientId.getId();
        Session session = sessionManager.getSession(sessionId);
        assertThat(session).isNotNull()
                .matches(t -> t.getClientId()
                        .equals(clientId));

        String cacheKey = String.format(CacheConstant.SESSION_KEY, clientId);
        RMap<String, Object> map = redissonClient.getMap(cacheKey);
        assertThat(map.get(GatewayConstant.NODE)).isNotNull()
                .matches(t -> t instanceof String)
                .matches(GlobalNodeId.getNodeId()::equals);
        assertThat(map.get(GatewayConstant.SESSION_ID)).isNotNull()
                .matches(t -> t instanceof String)
                .matches(sessionId::equals);
        assertThat(map.get(GatewayConstant.LAST_ACTIVE_TIME)).isNotNull();

        map.delete();
    }

    @Test
    public void test_removeSession() {
        doReturn(new DefaultChannelPromise(channel)).when(channel)
                .close();

        String clientId = UUID.randomUUID()
                .toString();
        Session sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNull();

        sessionManager.createSession(clientId, channel);

        sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNotNull()
                .matches(session -> session.getClientId()
                        .equals(clientId));
        String sessionId = sessionByClientId.getId();
        Session session = sessionManager.getSession(sessionId);
        assertThat(session).isNotNull()
                .matches(t -> t.getClientId()
                        .equals(clientId));

        sessionManager.removeSession(sessionId);
        sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNull();
        session = sessionManager.getSession(sessionId);
        assertThat(session).isNull();
        String cacheKey = String.format(CacheConstant.SESSION_KEY, clientId);
        RMap<String, Object> map = redissonClient.getMap(cacheKey);
        assertThat(map.isExists()).isFalse();
    }

    @Test
    public void test_updateLastActiveTime() throws InterruptedException {
        doReturn(new DefaultChannelPromise(channel)).when(channel)
                .close();

        String clientId = UUID.randomUUID()
                .toString();
        sessionManager.createSession(clientId, channel);
        Session sessionByClientId = sessionManager.getSessionByClientId(clientId);
        assertThat(sessionByClientId).isNotNull();

        String cacheKey = String.format(CacheConstant.SESSION_KEY, clientId);
        RMap<String, Object> map = redissonClient.getMap(cacheKey);
        Object lastActiveTime = map.get(GatewayConstant.LAST_ACTIVE_TIME);
        assertThat(lastActiveTime).isNotNull()
                .matches(t -> t instanceof Long);

        TimeUnit.MILLISECONDS.sleep(50);
        sessionManager.updateLastActiveTime(sessionByClientId.getId());

        map = redissonClient.getMap(cacheKey);
        Object lastActiveTime2 = map.get(GatewayConstant.LAST_ACTIVE_TIME);
        assertThat(lastActiveTime2).isNotNull()
                .matches(t -> t instanceof Long)
                .matches(t -> lastActiveTime2 != lastActiveTime)
                .matches(t -> (long) lastActiveTime2 > (long) lastActiveTime);

        sessionManager.removeSession(sessionByClientId.getId());
    }
}
