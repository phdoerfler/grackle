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
import io.circe.Json
import munit.CatsEffectSuite

import grackle.benchmarks.sql.{AdventureWorksMapping, BenchmarkDb, JoinChain}

/**
 * Cross-arm parity check: verifies Grackle's arm and the naive ORM arm traverse the SAME
 * underlying row set, not just that each individually produces output without errors
 * (`GrackleShapeQuerySuite`, `NaiveOrmArmSuite`) or that the ORM arms agree with each other
 * (`EagerOrmArmSuite`). Nothing else in this suite checks the two different technology stacks —
 * Grackle's LEFT-JOIN-based SQL and Hibernate's lazy entity traversal — actually reach the same
 * data.
 *
 * This matters specifically because of `BusinessEntityAddressEntity.person`'s
 * `@NotFound(action = NotFoundAction.IGNORE)` mapping (see `AdventureWorksEntities.scala`),
 * which was chosen specifically to mimic Grackle's own nullable LEFT JOIN semantics for that
 * relation (a `businessEntityAddress` row whose `businessEntityId` belongs to a Store/Vendor,
 * not a Person, resolves to `null` on both sides rather than being skipped or erroring). That
 * equivalence claim was previously only asserted in a code comment; this test actually
 * exercises it end to end.
 *
 * Uses the `deep-narrow` shape (full depth 10, one field per hop): the shape most directly
 * comparable across arms, since neither `wideFields` nor `tuned` changes which rows are
 * reached, only which columns are selected or how many statements are issued (see
 * `OrmQueryCounts` and `OrmVsGrackleBenchmark`'s class docs on that distinction).
 */
class GrackleVsOrmParitySuite extends CatsEffectSuite {
  val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])
  val factory = OrmDb.emf()
  override def afterAll(): Unit = factory.close()

  // (GraphQL field name, isList) for each hop from CountryRegion down to ProductCategory,
  // matching JoinChain.hops in traversal order. Same list AdventureWorksMappingSuite
  // (benchmarksSql) uses to walk a single path through the result; this one collects every leaf
  // reached, not just the first, since the naive ORM arm's `List[String]` is a full leaf-name
  // multiset, not a single sample.
  val hopsWithCardinality: List[(String, Boolean)] =
    List(
      "stateProvinces" -> true,
      "addresses" -> true,
      "businessEntityAddresses" -> true,
      "person" -> false,
      "customers" -> true,
      "salesOrders" -> true,
      "lineItems" -> true,
      "product" -> false,
      "subcategory" -> false,
      "category" -> false
    )

  /**
   * Every JSON node reached by following every branch of `hops` from `current`.
   */
  def descendAll(current: Json, hops: List[(String, Boolean)]): List[Json] =
    hops match {
      case Nil => List(current)
      case (field, isList) :: rest =>
        current.hcursor.downField(field).focus match {
          case None => Nil
          case Some(next) =>
            if (isList)
              next.asArray.getOrElse(Vector.empty).toList.flatMap(descendAll(_, rest))
            else if (next.isNull) Nil
            else descendAll(next, rest)
        }
    }

  test(
    "grackle arm and naive ORM arm reach the same set of category names for shape deep-narrow") {
    val shape = OrmQueryShapes.deepNarrow
    val rootCode = JoinChain.defaultRootCode

    val em = factory.createEntityManager()
    val naiveNames =
      try NaiveOrmArm.run(em, shape, rootCode)
      finally em.close()

    mapping.compileAndRun(GrackleShapeQuery.queryFor(shape, rootCode)).map { result =>
      assert(
        !result.hcursor.downField("errors").succeeded,
        s"unexpected GraphQL errors: $result")

      val countryRegions =
        result
          .hcursor
          .downField("data")
          .downField("countryRegions")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)

      val grackleNames =
        countryRegions
          .toList
          .flatMap(cr => descendAll(cr, hopsWithCardinality))
          .flatMap(_.hcursor.downField("name").as[String].toOption)

      assert(naiveNames.nonEmpty, "naive arm reached no category names — test proves nothing")
      assertEquals(
        grackleNames.sorted,
        naiveNames.sorted,
        "grackle arm and naive ORM arm should traverse the same underlying category-name row set")
    }
  }
}
