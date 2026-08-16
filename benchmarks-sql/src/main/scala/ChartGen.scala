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
import java.nio.file.{Files, Path, Paths}

/**
 * Renders the benchmark charts as standalone SVG files from [[ChartData]], and embeds them into
 * `benchmarks-sql/README.md` as `<img>` tags between per-chart HTML-comment markers. SVG rather
 * than mermaid because these are grouped bar charts with a legend, which mermaid's
 * `xychart-beta` cannot express (no legend, single-series only).
 *
 * `sbt benchmarksSql/generateCharts` writes the `charts/<id>.svg` files and the README `<img>`
 * tags; `sbt benchmarksSql/checkCharts` regenerates both in memory and fails if either differs
 * from what is committed — the regenerate-and-diff guard the repo already applies to its
 * generated CI workflows, needing no database. The SVG is deterministic (integer coordinates,
 * no generated ids) so that comparison is byte-for-byte, and bakes in a light panel so it stays
 * legible on GitHub in both light and dark themes, where an `<img>` cannot inherit the page's
 * colours.
 *
 * Usage: `ChartGen <generate|check> <moduleBaseDir>`.
 */
object ChartGen {

  private object Palette {
    val panel = "#ffffff"
    val border = "#e2e6ec"
    val ink = "#161b24"
    val muted = "#5b6472"
    val grid = "#eceff3"
    val axis = "#aab2bf"
    val naive = "#d1495b"
    val eager = "#e0a458"
    val grackle = "#1f7a5a"
    val unfiltered = "#1f5fa8"
  }

  final case class Series(name: String, color: String, values: List[Int])

  final case class BarChart(
      id: String,
      title: String,
      categories: List[String],
      series: List[Series],
      yMax: Int,
      xTitle: Option[String] = None,
      valueLabels: Boolean = false,
      yFormat: Int => String = _.toString) {
    val altText: String =
      s"$title. " + series
        .map(s => s"${s.name}: ${s.values.mkString(", ")}")
        .mkString("; ") + "."
  }

  def charts(data: ChartData): List[BarChart] = {
    val s = data.statementsPerShape
    val r = data.rowsByDepth
    List(
      BarChart(
        id = "statements-per-shape",
        title = "SQL statements per query shape",
        categories = s.shapes,
        series = List(
          Series("naive ORM", Palette.naive, s.naive),
          Series("eager ORM", Palette.eager, s.eager),
          Series("Grackle", Palette.grackle, s.grackle)),
        yMax = axisMax((s.naive ++ s.eager ++ s.grackle).max),
        valueLabels = true
      ),
      BarChart(
        id = "statements-by-depth",
        title = "SQL statements vs join depth",
        categories = data.grackleStatementsByDepth.depths.map(_.toString),
        series = List(
          Series("naive ORM", Palette.naive, data.naiveStatementsByDepth.statements),
          Series("Grackle", Palette.grackle, data.grackleStatementsByDepth.statements)),
        yMax = axisMax(
          (data
            .naiveStatementsByDepth
            .statements ++ data.grackleStatementsByDepth.statements).max),
        xTitle = Some("join depth")
      ),
      BarChart(
        id = "rows-by-depth",
        title = "Rows fetched vs join depth (1 statement throughout)",
        categories = r.depths.map(_.toString),
        series = List(
          Series("unfiltered", Palette.unfiltered, r.unfiltered),
          Series("FR", Palette.eager, r.filtered)),
        yMax = axisMax((r.unfiltered ++ r.filtered).max),
        xTitle = Some("join depth"),
        yFormat = thousands
      )
    )
  }

  // --- SVG rendering --------------------------------------------------------------------------

  private val W = 640
  private val H = 360
  private val mTop = 64 // title + legend
  private val mBottom = 52 // x labels + x title
  private val mLeft = 64
  private val mRight = 20
  private val plotW = W - mLeft - mRight
  private val plotH = H - mTop - mBottom
  private val ticks = 5

  def renderSvg(c: BarChart): String = {
    def y(v: Int): Int = mTop + plotH - Math.round(v.toDouble / c.yMax * plotH).toInt

    val elems = scala.collection.mutable.ListBuffer.empty[String]

    elems += rect(0, 0, W, H, Palette.panel, Some(Palette.border))
    elems += text(mLeft, 26, c.title, "start", 15, Palette.ink, bold = true)

    // Legend (only meaningful with more than one series).
    if (c.series.length > 1) {
      var lx = mLeft
      val ly = 44
      c.series.foreach { s =>
        elems += rect(lx, ly - 9, 12, 12, s.color, None)
        elems += text(lx + 18, ly, s.name, "start", 12, Palette.ink)
        lx += 18 + 8 + s.name.length * 7 + 18
      }
    }

    // Gridlines + y-axis tick labels.
    (0 to ticks).foreach { i =>
      val v = c.yMax * i / ticks
      val yy = y(v)
      elems += line(mLeft, yy, mLeft + plotW, yy, Palette.grid, 1)
      elems += text(mLeft - 8, yy + 4, c.yFormat(v), "end", 11, Palette.muted)
    }
    elems += line(mLeft, y(0), mLeft + plotW, y(0), Palette.axis, 2)

    // Grouped bars.
    val groupW = plotW.toDouble / c.categories.length
    val inner = groupW * 0.66
    val bw = inner / c.series.length
    c.categories.zipWithIndex.foreach {
      case (cat, ci) =>
        val gx = mLeft + ci * groupW + (groupW - inner) / 2
        c.series.zipWithIndex.foreach {
          case (s, si) =>
            val v = s.values(ci)
            val bx = Math.round(gx + si * bw).toInt
            val by = y(v)
            val bh = Math.max(1, y(0) - by)
            elems += rect(bx + 1, by, Math.round(bw).toInt - 2, bh, s.color, None)
            if (c.valueLabels)
              elems += text(
                bx + Math.round(bw).toInt / 2,
                by - 4,
                v.toString,
                "middle",
                10,
                Palette.ink,
                bold = true)
        }
        val cx = Math.round(mLeft + ci * groupW + groupW / 2).toInt
        elems += text(cx, H - mBottom + 20, cat, "middle", 11, Palette.ink)
    }

    c.xTitle
      .foreach(t => elems += text(mLeft + plotW / 2, H - 8, t, "middle", 11, Palette.muted))

    s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $W $H" width="$W" height="$H" font-family="ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, sans-serif">
       |${elems.map("  " + _).mkString("\n")}
       |</svg>
       |""".stripMargin
  }

  private def rect(
      x: Int,
      y: Int,
      w: Int,
      h: Int,
      fill: String,
      stroke: Option[String]): String = {
    val strokeAttr = stroke.map(s => s""" stroke="$s"""").getOrElse("")
    s"""<rect x="$x" y="$y" width="$w" height="$h" rx="2" fill="$fill"$strokeAttr/>"""
  }

  private def line(x1: Int, y1: Int, x2: Int, y2: Int, stroke: String, width: Int): String =
    s"""<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="$stroke" stroke-width="$width"/>"""

  private def text(
      x: Int,
      y: Int,
      s: String,
      anchor: String,
      size: Int,
      fill: String,
      bold: Boolean = false): String = {
    val weight = if (bold) """ font-weight="600"""" else ""
    s"""<text x="$x" y="$y" text-anchor="$anchor" font-size="$size" fill="$fill"$weight>${escape(
        s)}</text>"""
  }

  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def thousands(v: Int): String = if (v >= 1000) s"${v / 1000}k" else v.toString

  /**
   * A "nice" round upper bound just above `max`: 5 for tiny series, otherwise the next multiple
   * of half the leading power of ten (e.g. 272 -> 300, 61898 -> 65000). Deterministic so
   * `check` is a byte-for-byte comparison.
   */
  def axisMax(max: Int): Int =
    if (max <= 5) 5
    else {
      val magnitude = math.pow(10, math.floor(math.log10(max.toDouble))).toInt
      val step = math.max(magnitude / 2, 1)
      (max / step + 1) * step
    }

  // --- README splicing ------------------------------------------------------------------------

  private def startMarker(id: String): String = s"<!-- CHART:$id START -->"
  private def endMarker(id: String): String = s"<!-- CHART:$id END -->"

  private def imgTag(c: BarChart): String =
    s"""<img src="charts/${c.id}.svg" alt="${escape(c.altText)}">"""

  /**
   * Replaces the text between a chart's START and END markers with `block`.
   */
  def splice(doc: String, id: String, block: String): String = {
    val start = startMarker(id)
    val end = endMarker(id)
    val si = doc.indexOf(start)
    val ei = doc.indexOf(end)
    require(si >= 0, s"missing marker: $start")
    require(ei > si, s"missing or misordered marker: $end")
    doc.substring(0, si + start.length) + "\n" + block + "\n" + doc.substring(ei)
  }

  /**
   * Applies every chart's `<img>` tag to `doc`, leaving all other content untouched.
   */
  def renderReadme(doc: String, data: ChartData): String =
    charts(data).foldLeft(doc)((acc, c) => splice(acc, c.id, imgTag(c)))

  def main(args: Array[String]): Unit = {
    val (mode, baseDir) = args.toList match {
      case m :: b :: Nil => (m, b)
      case _ => sys.error("usage: ChartGen <generate|check> <moduleBaseDir>")
    }
    val base = Paths.get(baseDir)
    val readmePath = base.resolve("README.md")
    val chartsDir = base.resolve("charts")
    val data = ChartData.load

    val readmeNow = readString(readmePath)
    val readmeNext = renderReadme(readmeNow, data)
    val svgs = charts(data).map(c => (chartsDir.resolve(s"${c.id}.svg"), renderSvg(c)))

    mode match {
      case "generate" =>
        val _ = Files.createDirectories(chartsDir)
        var changed = 0
        if (readmeNext != readmeNow) { writeString(readmePath, readmeNext); changed += 1 }
        svgs.foreach {
          case (p, svg) =>
            if (!Files.exists(p) || readString(p) != svg) { writeString(p, svg); changed += 1 }
        }
        println(
          if (changed == 0) "charts already up to date"
          else s"regenerated $changed chart file(s)")
      case "check" =>
        val stale =
          (if (readmeNext != readmeNow) List(readmePath.toString) else Nil) ++
            svgs.collect {
              case (p, svg) if !Files.exists(p) || readString(p) != svg => p.toString
            }
        if (stale.isEmpty) println("charts up to date")
        else {
          System
            .err
            .println(
              "charts are out of sync with chart-data.json — run `sbt benchmarksSql/generateCharts`:\n  " +
                stale.mkString("\n  "))
          sys.exit(1)
        }
      case other => sys.error(s"unknown mode '$other' (expected generate or check)")
    }
  }

  private def readString(p: Path): String =
    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def writeString(p: Path, s: String): Unit = {
    Files.write(p, s.getBytes(StandardCharsets.UTF_8))
    ()
  }
}
