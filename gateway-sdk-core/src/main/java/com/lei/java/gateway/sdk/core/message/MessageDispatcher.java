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
package com.lei.java.gateway.sdk.core.message;

import java.util.concurrent.CompletableFuture;

import com.lei.java.gateway.sdk.core.domain.PushResult;

/**
 * <p>
 * MessageDispatcher
 * </p>
 *
 * @author 伍磊
 */
public interface MessageDispatcher {

    CompletableFuture<PushResult> dispatch(Long requestId, String clientId, byte[] content);

    void shutdown();

}
