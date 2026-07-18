// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2025 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package demo.felines

import cats.effect.IO
import cats.syntax.all._
import org.http4s.{Header, Headers, HttpRoutes, Request}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Connection
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci._
import org.typelevel.log4cats.Logger

import demo.felines.transport
import grackle.Mapping

// Dispatches requests at /cats to one of three subscription transports (or falls through to the
// plain HTTP query/mutation route already built by GraphQLService, composed alongside this in
// Main.scala) based on standard, unambiguous signals: a WebSocket upgrade (RFC 6455 mandates
// Connection: Upgrade + Upgrade: websocket for the handshake to be valid at all), further
// distinguishing the graphql-transport-ws vs subscriptions-transport-ws dialect via
// Sec-WebSocket-Protocol; or Accept: text/event-stream for Server-Sent Events. Neither signal
// present falls through untouched to the existing plain-HTTP route.
object CatsRoutes {

  sealed trait WsProtocol
  object WsProtocol {
    case object Modern extends WsProtocol // graphql-transport-ws
    case object Legacy extends WsProtocol // subscriptions-transport-ws, subprotocol "graphql-ws"
  }

  def isWebSocketUpgrade(req: Request[IO]): Boolean =
    req.headers.get[Connection].exists(_.hasUpgrade)

  def acceptsEventStream(req: Request[IO]): Boolean =
    req
      .headers
      .get(ci"Accept")
      .exists(_.exists(_.value.toLowerCase.contains("text/event-stream")))

  // Splits each raw Sec-WebSocket-Protocol header line on commas (a client may offer several,
  // comma-separated, in preference order) before matching tokens exactly - a substring check
  // alone would be fine here too ("graphql-ws" is not a substring of "graphql-transport-ws"), but
  // exact-token matching after splitting is the technically correct way to read a header that's
  // explicitly defined as a comma-separated list.
  def offeredWsProtocols(req: Request[IO]): List[String] =
    req
      .headers
      .get(ci"Sec-WebSocket-Protocol")
      .toList
      .flatMap(_.toList)
      .flatMap(_.value.split(",").toList)
      .map(_.trim.toLowerCase)

  // DIAGNOSTIC DEFAULT — Task 1/2 only, see Task 3 Step 2 for the real, evidence-based default.
  def negotiateWsProtocol(offered: List[String]): WsProtocol =
    if (offered.contains("graphql-ws") && !offered.contains("graphql-transport-ws"))
      WsProtocol.Legacy
    else
      WsProtocol.Modern

  def routes(wsb: WebSocketBuilder2[IO], mapping: Mapping[IO], logger: Logger[IO]): HttpRoutes[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "cats" if isWebSocketUpgrade(req) =>
        val offered = offeredWsProtocols(req)
        val protocol = negotiateWsProtocol(offered)
        val confirmedWsb =
          if (offered.isEmpty) wsb // RFC 6455: nothing was offered, so confirm nothing
          else {
            val token = protocol match {
              case WsProtocol.Modern => "graphql-transport-ws"
              case WsProtocol.Legacy => "graphql-ws"
            }
            wsb.withHeaders(Headers(Header.Raw(ci"Sec-WebSocket-Protocol", token)))
          }
        protocol match {
          case WsProtocol.Modern => transport.ModernGraphQLWs.handler(confirmedWsb, mapping, logger)
          case WsProtocol.Legacy =>
            logger.info(s"[cats ws diagnostic] legacy dialect chosen, offered=$offered") *>
              confirmedWsb.build(fs2.Stream.empty, _.void)
        }
    }
  }
}
