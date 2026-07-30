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

object JoinChain {

  // GraphQL field name for each hop, root (CountryRegion) to leaf (ProductCategory).
  // Must match the field names in AdventureWorksMapping's schema exactly.
  val hops: List[String] =
    List(
      "stateProvinces",
      "addresses",
      "businessEntityAddresses",
      "person",
      "customers",
      "salesOrders",
      "lineItems",
      "product",
      "subcategory",
      "category"
    )

  val maxDepth: Int = hops.length

  private val leafField: Map[String, String] =
    Map(
      "stateProvinces" -> "name",
      "addresses" -> "city",
      "businessEntityAddresses" -> "addressTypeId",
      "person" -> "lastName",
      "customers" -> "territoryId",
      "salesOrders" -> "totalDue",
      "lineItems" -> "unitPrice",
      "product" -> "name",
      "subcategory" -> "name",
      "category" -> "name"
    )

  def queryForDepth(depth: Int): String = {
    require(
      depth >= 1 && depth <= maxDepth,
      s"depth must be between 1 and $maxDepth, got $depth")

    def nest(remaining: List[String]): String =
      remaining match {
        case field :: Nil => s"$field { ${leafField(field)} }"
        case field :: rest => s"$field { ${nest(rest)} }"
        case Nil => throw new IllegalStateException("unreachable: depth bounds checked above")
      }

    s"query { countryRegions { countryRegionCode ${nest(hops.take(depth))} } }"
  }
}
