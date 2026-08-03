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

package grackle.benchmarks.orm

import cats.effect.IO
import cats.syntax.all._
import munit.CatsEffectSuite

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb}

class GrackleShapeQuerySuite extends CatsEffectSuite {
  val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])

  test("every shape's query text resolves without GraphQL errors") {
    OrmQueryShapes.all.traverse_ { shape =>
      mapping.compileAndRun(GrackleShapeQuery.queryFor(shape)).map { result =>
        assert(
          !result.hcursor.downField("errors").succeeded,
          s"shape ${shape.name} produced GraphQL errors: $result")
      }
    }
  }
}
