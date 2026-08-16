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
    assertEquals(data.naiveStatementsByDepth.statements.length, 10)
    assert(
      data.naiveStatementsByDepth.statements.last > data.naiveStatementsByDepth.statements.head,
      "naive per-depth counts should climb with depth")
    assertEquals(data.rowsByDepth.unfiltered.last, 61898)
    assertEquals(data.rowsByDepth.filtered.last, 5677)
  }

  test("provisional timing data loads and drives the latency chart") {
    val timing = TimingData.load
    assertEquals(timing.untunedMsVsRtt.latencyMs, List(0, 20, 50))
    val c = ChartGen.timingChart(timing)
    assertEquals(c.id, "untuned-ms-vs-rtt")
    assertEquals(c.series.length, 3)
    assert(c.title.contains("indicative"), "the timing chart must be labelled indicative")
  }

  test("axisMax rounds to a nice bound just above the max") {
    assertEquals(ChartGen.axisMax(1), 5)
    assertEquals(ChartGen.axisMax(5), 5)
    assertEquals(ChartGen.axisMax(272), 300)
    assertEquals(ChartGen.axisMax(61898), 65000)
  }

  test("the statements-per-shape SVG is well-formed and carries the data") {
    val c = ChartGen.charts(data).find(_.id == "statements-per-shape").get
    val svg = ChartGen.renderSvg(c)

    assert(svg.startsWith("<svg "), "should be an SVG document")
    assert(svg.trim.endsWith("</svg>"), "should be closed")
    // One <rect> panel + one per (category x series) bar; legend adds one swatch <rect> per series.
    val bars = 4 * 3
    val rects = "<rect".r.findAllIn(svg).length
    assertEquals(rects, 1 + bars + c.series.length, "panel + bars + legend swatches")
    // Legend names and a value label are present.
    assert(svg.contains(">naive ORM<"), "legend should name the naive arm")
    assert(svg.contains(">Grackle<"), "legend should name Grackle")
    assert(svg.contains(">272<"), "a value label should be rendered")
    // No generated ids or locale-dependent decimals, so `check` can diff byte-for-byte. A
    // locale-formatted coordinate would show a comma immediately before a digit (e.g. "12,5"); the
    // commas in font-family lists are always followed by a space, so this stays clean.
    assert(!svg.contains("id="), "SVG must not contain generated ids")
    assert(
      ",\\d".r.findFirstIn(svg).isEmpty,
      "coordinates must be integers, not locale decimals")
  }

  test("the rows SVG formats large y-axis ticks in thousands") {
    val c = ChartGen.charts(data).find(_.id == "rows-by-depth").get
    val svg = ChartGen.renderSvg(c)
    assert(svg.contains(">65k<"), "y-axis top tick should read 65k")
    assert(svg.contains(">13k<"), "y-axis ticks should be in thousands")
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

  test("renderReadme injects an img tag per chart and is idempotent") {
    val chartList = ChartGen.allCharts(data, TimingData.load)
    val doc = chartList
      .map(_.id)
      .map(id => s"<!-- CHART:$id START -->\n<!-- CHART:$id END -->")
      .mkString("\n\n")
    val once = ChartGen.renderReadme(doc, chartList)
    val twice = ChartGen.renderReadme(once, chartList)
    assertEquals(twice, once)
    assert(
      once.contains("""<img src="charts/statements-per-shape.svg""""),
      "should embed the generated SVG by relative path")
    assert(
      once.contains("""<img src="charts/untuned-ms-vs-rtt.svg""""),
      "should embed the timing chart too")
  }

  test("splice fails loudly on a missing marker") {
    intercept[IllegalArgumentException](ChartGen.splice("no markers here", "x", "block"))
  }
}
