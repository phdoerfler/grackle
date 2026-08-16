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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * Renders the mermaid `xychart-beta` blocks in `benchmarks-sql/README.md` from [[ChartData]] and
 * splices them into the README between per-chart HTML-comment markers, so the numbers in the docs
 * come from one committed source rather than being hand-maintained.
 *
 * Run `sbt benchmarksSql/generateCharts` to rewrite the README in place; CI runs
 * `sbt benchmarksSql/checkCharts`, which regenerates into memory and fails if the committed README
 * differs — the same regenerate-and-diff guard the repo already applies to its generated CI
 * workflows. Only the fenced blocks between the markers are owned by the generator; the surrounding
 * prose and captions stay hand-edited.
 *
 * Usage: `ChartGen <generate|check> <readmePath>`.
 */
object ChartGen {

  final case class Chart(id: String, block: String)

  def charts(data: ChartData): List[Chart] = List(
    Chart("statements-per-shape", statementsPerShape(data.statementsPerShape)),
    Chart("grackle-statements-by-depth", grackleStatementsByDepth(data.grackleStatementsByDepth)),
    Chart("rows-by-depth", rowsByDepth(data.rowsByDepth)))

  def statementsPerShape(s: StatementsPerShape): String =
    xychart(
      title = "SQL statements per query shape",
      xAxis = categorical(s.shapes),
      yLabel = "statements issued",
      yMax = axisMax((s.naive ++ s.eager ++ s.grackle).max),
      series = List(s.naive, s.eager, s.grackle))

  def grackleStatementsByDepth(s: SeriesByDepth): String =
    xychart(
      title = "Grackle SQL statements vs join depth",
      xAxis = numeric(s.depths),
      yLabel = "statements issued",
      yMax = axisMax(s.statements.max),
      series = List(s.statements))

  def rowsByDepth(r: RowsByDepth): String =
    xychart(
      title = "Rows fetched vs join depth (1 statement throughout)",
      xAxis = numeric(r.depths),
      yLabel = "rows fetched",
      yMax = axisMax((r.unfiltered ++ r.filtered).max),
      series = List(r.unfiltered, r.filtered))

  private def categorical(labels: List[String]): String =
    labels.map(l => "\"" + l + "\"").mkString("[", ", ", "]")

  private def numeric(values: List[Int]): String =
    values.mkString("[", ", ", "]")

  private def xychart(
      title: String,
      xAxis: String,
      yLabel: String,
      yMax: Int,
      series: List[List[Int]]): String = {
    val lines = series.map(s => s"  line ${numeric(s)}").mkString("\n")
    s"""```mermaid
       |xychart-beta
       |  title "$title"
       |  x-axis $xAxis
       |  y-axis "$yLabel" 0 --> $yMax
       |$lines
       |```""".stripMargin
  }

  /**
   * A "nice" round upper bound just above `max`: 5 for tiny series, otherwise the next multiple of
   * half the leading power of ten (e.g. 272 -> 300, 61898 -> 65000). Deterministic so `check` is a
   * byte-for-byte comparison.
   */
  def axisMax(max: Int): Int =
    if (max <= 5) 5
    else {
      val magnitude = math.pow(10, math.floor(math.log10(max.toDouble))).toInt
      val step = math.max(magnitude / 2, 1)
      (max / step + 1) * step
    }

  private def startMarker(id: String): String = s"<!-- CHART:$id START -->"
  private def endMarker(id: String): String = s"<!-- CHART:$id END -->"

  /** Replaces the text between a chart's START and END markers with `block`. */
  def splice(doc: String, id: String, block: String): String = {
    val start = startMarker(id)
    val end = endMarker(id)
    val si = doc.indexOf(start)
    val ei = doc.indexOf(end)
    require(si >= 0, s"missing marker: $start")
    require(ei > si, s"missing or misordered marker: $end")
    doc.substring(0, si + start.length) + "\n" + block + "\n" + doc.substring(ei)
  }

  /** Applies every chart's block to `doc`, leaving all other content untouched. */
  def render(doc: String, data: ChartData): String =
    charts(data).foldLeft(doc)((acc, c) => splice(acc, c.id, c.block))

  def main(args: Array[String]): Unit = {
    val (mode, readmePath) = args.toList match {
      case m :: p :: Nil => (m, p)
      case _ => sys.error("usage: ChartGen <generate|check> <readmePath>")
    }
    val path = Paths.get(readmePath)
    val current = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    val updated = render(current, ChartData.load)

    mode match {
      case "generate" =>
        if (updated == current) println(s"charts already up to date: $readmePath")
        else {
          Files.write(path, updated.getBytes(StandardCharsets.UTF_8))
          println(s"regenerated charts in $readmePath")
        }
      case "check" =>
        if (updated == current) println(s"charts up to date: $readmePath")
        else {
          System.err.println(
            s"$readmePath is out of sync with chart-data.json — run `sbt benchmarksSql/generateCharts`")
          sys.exit(1)
        }
      case other => sys.error(s"unknown mode '$other' (expected generate or check)")
    }
  }
}
