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

package grackle.benchmarks.sql

import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import munit.CatsEffectSuite

class AdventureWorksMappingSuite extends CatsEffectSuite {

  val mapping = AdventureWorksMapping.mkMapping[IO](BenchmarkDb.transactor[IO])

  // (GraphQL field name, isList) for each hop from CountryRegion down to
  // ProductCategory, matching JoinChain.hops in traversal order.
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

  def descend(current: Json, hops: List[(String, Boolean)]): Option[Json] =
    hops match {
      case Nil => Some(current)
      case (field, isList) :: rest =>
        current.hcursor.downField(field).focus.flatMap { next =>
          if (isList)
            next
              .asArray
              .getOrElse(Vector.empty)
              .view
              .flatMap(elem => descend(elem, rest))
              .headOption
          else if (next.isNull) None
          else descend(next, rest)
        }
    }

  assertEquals(hopsWithCardinality.map(_._1), JoinChain.hops)

  val knownCategoryNames: Set[String] = Set("Bikes", "Components", "Clothing", "Accessories")

  test("depth-10 query resolves a full join chain down to a known product category") {
    mapping.compileAndRun(JoinChain.queryForDepth(JoinChain.maxDepth)).map { result =>
      val cursor = result.hcursor
      assert(!cursor.downField("errors").succeeded, s"unexpected GraphQL errors: $result")

      val countryRegions =
        cursor
          .downField("data")
          .downField("countryRegions")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)

      val found = countryRegions.view.flatMap(cr => descend(cr, hopsWithCardinality)).headOption
      assert(found.isDefined, s"no country region reached a full depth-10 chain: $result")

      val categoryName = found.get.hcursor.downField("name").as[String].getOrElse("")
      assert(knownCategoryNames(categoryName), s"unexpected category name: $categoryName")
    }
  }

  test("every depth from 1 to maxDepth resolves without GraphQL errors") {
    (1 to JoinChain.maxDepth).toList.traverse { depth =>
      mapping.compileAndRun(JoinChain.queryForDepth(depth)).map { result =>
        val cursor = result.hcursor
        assert(
          !cursor.downField("errors").succeeded,
          s"depth $depth produced GraphQL errors: $result")
      }
    }
  }

  test("max-depth query returns real data for the root codes we actually run") {
    List(JoinChain.defaultRootCode, "US").traverse_ { code =>
      mapping.compileAndRun(JoinChain.queryForDepth(JoinChain.maxDepth, code)).map { result =>
        val cursor = result.hcursor
        assert(!cursor.downField("errors").succeeded, s"GraphQL errors for $code: $result")

        val regions =
          cursor
            .downField("data")
            .downField("countryRegions")
            .focus
            .flatMap(_.asArray)
            .getOrElse(Vector.empty)

        assertEquals(regions.size, 1, s"root filter should select exactly one region for $code")
        assert(
          regions.view.flatMap(cr => descend(cr, hopsWithCardinality)).headOption.isDefined,
          s"root $code did not reach full depth — an empty benchmark looks fast"
        )
      }
    }
  }
}
