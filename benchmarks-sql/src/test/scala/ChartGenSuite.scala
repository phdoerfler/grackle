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

import munit.FunSuite

class ChartGenSuite extends FunSuite {

  val data: ChartData = ChartData.load

  test("chart data loads from the classpath resource") {
    assertEquals(data.statementsPerShape.shapes.length, 4)
    assertEquals(data.grackleStatementsByDepth.statements, List.fill(10)(1))
    assertEquals(data.rowsByDepth.unfiltered.last, 61898)
    assertEquals(data.rowsByDepth.filtered.last, 5677)
  }

  test("axisMax rounds to a nice bound just above the max") {
    assertEquals(ChartGen.axisMax(1), 5)
    assertEquals(ChartGen.axisMax(5), 5)
    assertEquals(ChartGen.axisMax(272), 300)
    assertEquals(ChartGen.axisMax(61898), 65000)
  }

  test("statements-per-shape renders the expected mermaid block") {
    val expected =
      """```mermaid
        |xychart-beta
        |  title "SQL statements per query shape"
        |  x-axis ["shallow-narrow", "deep-narrow", "deep-wide", "untuned"]
        |  y-axis "statements issued" 0 --> 300
        |  line [63, 271, 272, 260]
        |  line [1, 1, 1, 261]
        |  line [1, 1, 1, 1]
        |```""".stripMargin
    assertEquals(ChartGen.statementsPerShape(data.statementsPerShape), expected)
  }

  test("splice replaces only the content between a chart's markers") {
    val doc =
      """intro
        |<!-- CHART:x START -->
        |stale
        |<!-- CHART:x END -->
        |outro""".stripMargin
    val out = ChartGen.splice(doc, "x", "FRESH")
    assertEquals(
      out,
      """intro
        |<!-- CHART:x START -->
        |FRESH
        |<!-- CHART:x END -->
        |outro""".stripMargin)
  }

  test("render is idempotent — regenerating an up-to-date doc changes nothing") {
    val doc = ChartGen
      .charts(data)
      .map(_.id)
      .map(id => s"<!-- CHART:$id START -->\n<!-- CHART:$id END -->")
      .mkString("\n\n")
    val once = ChartGen.render(doc, data)
    val twice = ChartGen.render(once, data)
    assertEquals(twice, once)
    assert(once.contains("xychart-beta"), "rendered doc should contain the charts")
  }

  test("splice fails loudly on a missing marker") {
    intercept[IllegalArgumentException](ChartGen.splice("no markers here", "x", "block"))
  }
}
