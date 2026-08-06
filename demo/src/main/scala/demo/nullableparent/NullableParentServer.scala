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

package demo.nullableparent

import cats.effect.{ExitCode, IO, IOApp}

import demo.DemoServer.mkServer
import demo.GraphQLService.mkRoutes

/**
 * Interactive playground for the INNER-JOIN-under-LEFT-JOIN bug. See
 * `NullableParentMapping`'s doc comment for what it demonstrates.
 *
 * A separate entry point rather than a route added to `demo.Main`, deliberately: `Main`'s body
 * sits inside `// #main` mdoc markers and is included verbatim in the published documentation, so
 * adding to it would change the docs to describe a repro that has nothing to do with the tutorial.
 *
 * {{{
 * sbt pgUp
 * sbt "demo/runMain demo.nullableparent.NullableParentServer"
 *
 * # both rows, the second with "b": null
 * curl -s 'http://localhost:8080/nullrepro?query={as{name b{name}}}'
 *
 * # only a-with-b — the row whose nullable parent is null has vanished
 * curl -s 'http://localhost:8080/nullrepro?query={as{name b{name c{name}}}}'
 * }}}
 *
 * The tables are dropped and recreated at startup, so restarting resets whatever you changed.
 */
object NullableParentServer extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    (for {
      routes <- NullableParentMapping[IO].map(mkRoutes("nullrepro"))
      _ <- mkServer(routes)
    } yield ()).useForever
}
