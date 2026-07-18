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

package demo.felines.transport

import scala.concurrent.duration._

import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.Stream
import io.circe.{Json, JsonObject, parser}
import io.circe.syntax._
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.Logger

import grackle.Mapping

// Implements the legacy subscriptions-transport-ws protocol (Apollo's original 2016-era
// subscription transport, subprotocol string "graphql-ws" - confusingly the same name as the
// *modern* npm package, but a different protocol; see ModernGraphQLWs for that one). Deprecated
// upstream and unmaintained since ~2018, but still what a number of GUI GraphQL clients only
// support - see this branch's design doc for why this demo carries it anyway.
//
// Flow: client connects with subprotocol graphql-ws and sends connection_init; the server replies
// connection_ack. The client then sends `start` messages (each with an id); the server streams
// `data` frames and a final `complete`. A client `stop` cancels that subscription. The server also
// pushes periodic `ka` (keep-alive) frames with no reply expected, unlike the modern protocol's
// bidirectional ping/pong.
//
// Same caller-configures-the-header contract as ModernGraphQLWs: this module never sets its own
// Sec-WebSocket-Protocol response header (see the RFC 6455 fix in Task 2 Step 2 of this plan).
object LegacyGraphQLWs {

  private type Subs = Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]

  private def text(j: Json): WebSocketFrame = WebSocketFrame.Text(j.noSpaces)
  private val ackMsg = Json.obj("type" -> "connection_ack".asJson)
  private val kaMsg = Json.obj("type" -> "ka".asJson)
  private def dataMsg(id: String, payload: Json) =
    Json.obj("id" -> id.asJson, "type" -> "data".asJson, "payload" -> payload)
  private def completeMsg(id: String) =
    Json.obj("id" -> id.asJson, "type" -> "complete".asJson)
  private def errorMsg(id: String, msg: String) =
    Json.obj(
      "id" -> id.asJson,
      "type" -> "error".asJson,
      "payload" -> Json.obj("message" -> msg.asJson))

  def handler(wsb: WebSocketBuilder2[IO], mapping: Mapping[IO], logger: Logger[IO]): IO[Response[IO]] =
    for {
      out <- Queue.unbounded[IO, WebSocketFrame]
      subs <- IO.ref(Map.empty[String, Fiber[IO, Throwable, Unit]])
      keepAlive = Stream.awakeEvery[IO](20.seconds).as(text(kaMsg))
      resp <- wsb.build(
        Stream.fromQueueUnterminated(out).merge(keepAlive),
        _.foreach(frame => handle(mapping, logger, out, subs, frame)).onFinalize(cancelAll(subs))
      )
    } yield resp

  private def cancelAll(subs: Subs): IO[Unit] =
    subs.getAndSet(Map.empty).flatMap(_.values.toList.traverse_(_.cancel))

  private def handle(
      mapping: Mapping[IO],
      logger: Logger[IO],
      out: Queue[IO, WebSocketFrame],
      subs: Subs,
      frame: WebSocketFrame): IO[Unit] =
    frame match {
      case t: WebSocketFrame.Text =>
        parser.parse(t.str).toOption.flatMap(_.asObject) match {
          case None => IO.unit
          case Some(obj) =>
            obj("type").flatMap(_.asString) match {
              case Some("connection_init") => out.offer(text(ackMsg))
              case Some("start") => startSub(mapping, logger, out, subs, obj)
              case Some("stop") => obj("id").flatMap(_.asString).traverse_(stopSub(subs, _))
              case Some("connection_terminate") => cancelAll(subs)
              case _ => IO.unit
            }
        }
      case _ => IO.unit
    }

  private def startSub(
      mapping: Mapping[IO],
      logger: Logger[IO],
      out: Queue[IO, WebSocketFrame],
      subs: Subs,
      obj: JsonObject): IO[Unit] =
    (obj("id").flatMap(_.asString), obj("payload").flatMap(_.asObject)) match {
      case (Some(id), Some(payload)) =>
        payload("query").flatMap(_.asString) match {
          case None => out.offer(text(errorMsg(id, "Missing query")))
          case Some(query) =>
            val op = payload("operationName").flatMap(_.asString)
            val vars = payload("variables")
            val run =
              mapping
                .compileAndRunSubscription(query, op, vars)
                .evalMap(json => out.offer(text(dataMsg(id, json))))
                .compile
                .drain
                .attempt
                .flatMap {
                  case Right(_) => out.offer(text(completeMsg(id)))
                  case Left(e) =>
                    logger.warn(e)(s"legacy WS subscription $id failed") *>
                      out.offer(text(errorMsg(id, Option(e.getMessage).getOrElse(e.toString))))
                }
            run.start.flatMap(fib => subs.update(_.updated(id, fib)))
        }
      case _ => IO.unit
    }

  private def stopSub(subs: Subs, id: String): IO[Unit] =
    subs.modify(m => (m - id, m.get(id))).flatMap(_.traverse_(_.cancel))
}
