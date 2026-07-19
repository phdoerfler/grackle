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

package demo

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import demo.DemoServer.mkServer
import demo.GraphQLService.mkRoutes
import demo.felines.{CatsMapping, CatsRoutes}
import demo.starwars.StarWarsMapping
import demo.world.WorldMapping

// #main
object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val logger: Logger[IO] = Slf4jLogger.getLoggerFromName[IO]("demo.felines")
    (for {
      starWarsRoutes <- StarWarsMapping[IO].map(mkRoutes("starwars"))
      worldRoutes <- WorldMapping[IO].map(mkRoutes("world"))
      catsMapping <- CatsMapping.resource
      catsHttpRoutes = mkRoutes("cats")(catsMapping)
      _ <- mkServer(wsb =>
        starWarsRoutes <+> worldRoutes <+> CatsRoutes
          .routes(wsb, catsMapping, logger) <+> catsHttpRoutes)
    } yield ()).useForever
  }
}
// #main
