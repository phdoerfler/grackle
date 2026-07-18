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

package demo.cats

import cats.effect.{ExitCode, IO, IOApp}

object WatchCat extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    args match {
      case idStr :: Nil =>
        val id = idStr.toInt
        CatsMapping
          .resource
          .use { mapping =>
            IO.println(s"watching cat $id...") *>
              mapping
                .compileAndRunSubscription(
                  s"""
                  subscription {
                    catUpdated(id: $id) {
                      id
                      name
                      status
                      position
                      hairLength
                      updatedAt
                    }
                  }
                """
                )
                .evalMap(json => IO.println(json.spaces2))
                .compile
                .drain
          }
          .as(ExitCode.Success)
      case _ =>
        IO.println("Usage: sbt \"demo/runMain demo.cats.WatchCat <id>\"").as(ExitCode.Error)
    }
}
