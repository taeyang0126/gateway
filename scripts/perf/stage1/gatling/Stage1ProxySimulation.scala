/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stage1

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class Stage1ProxySimulation extends Simulation {

  private val baseUrl: String = sys.env.getOrElse("BASE_URL", "http://127.0.0.1:8080")
  private val durationMinutes: Int = sys.env.getOrElse("DURATION_MINUTES", "10").toInt
  private val requestsPerSecond: Double = sys.env.getOrElse("RPS", "2000").toDouble
  private val bodyBytes: Int = sys.env.getOrElse("BODY_BYTES", "1024").toInt
  private val scenarioPath: String = sys.env.getOrElse("SCENARIO_PATH", "/api/ping")
  private val scenarioMethod: String = sys.env.getOrElse("SCENARIO_METHOD", "GET").toUpperCase

  private val payload: String = "x" * bodyBytes

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json, text/plain, */*")
    .userAgentHeader("stage1-gatling")

  private val requestBuilder =
    scenarioMethod match {
      case "POST" =>
        http("stage1_proxy_request")
          .post(scenarioPath)
          .body(StringBody(payload))
          .header("Content-Type", "text/plain")
          .check(status.in(200, 201, 202, 204))
      case _ =>
        http("stage1_proxy_request")
          .get(scenarioPath)
          .check(status.in(200, 201, 202, 204))
    }

  private val scenarioChain = scenario("stage1-proxy").forever(exec(requestBuilder))

  setUp(
    scenarioChain.inject(
      constantUsersPerSec(requestsPerSecond).during(durationMinutes.minutes)
    )
  ).protocols(httpProtocol)
}
