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
import munit.CatsEffectSuite

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain}

/**
 * Cross-arm parity check: verifies Grackle's arm and the naive ORM arm produce the SAME
 * response document — not just that each individually produces output without errors
 * (`GrackleShapeQuerySuite`, `NaiveOrmArmSuite`) or that the ORM arms agree with each other
 * (`EagerOrmArmSuite`). Nothing else in this suite checks the two different technology stacks —
 * Grackle's LEFT-JOIN-based SQL and Hibernate's lazy entity traversal — actually reach the same
 * data and assemble it into the same shape.
 *
 * This matters specifically because of `BusinessEntityAddressEntity.person`'s
 * `@NotFound(action = NotFoundAction.IGNORE)` mapping (see `AdventureWorksEntities.scala`),
 * which was chosen specifically to mimic Grackle's own nullable LEFT JOIN semantics for that
 * relation (a `businessEntityAddress` row whose `businessEntityId` belongs to a Store/Vendor,
 * not a Person, resolves to `null` on both sides rather than being skipped or erroring). That
 * equivalence claim was previously only asserted in a code comment; this test actually
 * exercises it end to end.
 *
 * Runs both `deep-narrow` and `deep-wide` (full depth 10). `deep-narrow` (one field per hop) is
 * the shape most directly comparable across arms, since neither `wideFields` nor `tuned`
 * changes which rows are reached, only which columns are selected or how many statements are
 * issued (see `OrmQueryCounts` and `OrmVsGrackleBenchmark`'s class docs on that distinction).
 * `deep-wide` layers on top of that: because the comparison is whole-document equality rather
 * than a set of leaf names, it additionally proves the two arms select the SAME scalar fields
 * at every hop, not merely that they reach the same rows.
 */
class GrackleVsOrmParitySuite extends CatsEffectSuite {
  val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])
  val factory = OrmDb.emf()
  override def afterAll(): Unit = factory.close()

  List(OrmQueryShapes.deepNarrow, OrmQueryShapes.deepWide).foreach { shape =>
    test(s"grackle arm and naive ORM arm produce the same document for shape ${shape.name}") {
      val rootCode = JoinChain.defaultRootCode

      val em = factory.createEntityManager()
      val ormDoc =
        try NaiveOrmArm.run(em, shape, rootCode)
        finally em.close()

      mapping.compileAndRun(GrackleShapeQuery.queryFor(shape, rootCode)).map { grackleDoc =>
        assert(
          !grackleDoc.hcursor.downField("errors").succeeded,
          s"unexpected GraphQL errors: $grackleDoc")

        // Non-degenerate: two empty documents would satisfy the equality below and prove nothing.
        val names = JsonCanonical.categoryNames(ormDoc)
        assert(names.nonEmpty, s"ORM document reached no category names for ${shape.name}")

        assertEquals(
          JsonCanonical.canonicalize(ormDoc),
          JsonCanonical.canonicalize(grackleDoc),
          s"documents differ for shape ${shape.name}")
      }
    }
  }
}
