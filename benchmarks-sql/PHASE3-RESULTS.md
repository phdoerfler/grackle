# Phase 3 results — Grackle vs Hibernate under injected network latency

> **Superseded — awaiting a re-sweep.** These numbers were measured at commit `a2f6bd37`, when the
> ORM arms returned a `List[String]` of leaf category names and discarded everything else they
> fetched, while the Grackle arm assembled a full circe response document. The ORM arms now build
> the same document (see `Selection`, `OrmJson`, and `NaiveOrmArm`), so their figures here
> understate their per-invocation work. The prediction — not yet verified — is that the slope
> findings survive intact, because assembly is per-invocation CPU rather than round trips, and that
> only the zero-latency baselines and the `deep-wide` constant move.
>
> The ORM arms also now prune branches that the schema's non-null hops (`product: Product!`,
> `category: ProductCategory!`) eliminate from Grackle's result set, so both arms assemble the
> same nodes rather than the ORM arms assembling more.

Measured 2026-08-05 against `benchmark-postgres` (AdventureWorks) through Toxiproxy, on a WSL2
workstation. JMH `Mode.SampleTime`, `@Fork(1)`, 2 warmup + 5 measurement iterations at 5s each,
48 combinations (3 arms x 4 shapes x 4 latency levels).

JMH's own `results.json` is gitignored, so this file is the durable record of the numbers.

## What this measures, and why latency is the point

Phases 1 and 2 established the statement counts: Grackle emits **1** SQL statement for every query
shape at every depth; the naive Hibernate arm emits 63-272 depending on shape; the eager Hibernate
arm — blanket `@BatchSize` plus a per-shape `@EntityGraph` — matches Grackle's 1 on the three
shapes it was tuned for and collapses to ~261 on a fourth shape nobody tuned for.

Statement counts are deterministic and settle the N+1 argument outright, but on a local Docker
network they understate the cost enormously: round-trip time is near zero, so a few hundred extra
statements cost a few hundred milliseconds. In production the database is across a network, and
the N+1 penalty *is* a round-trip penalty. Phase 3 injects controllable RTT and sweeps it, so the
gap becomes a curve rather than an assertion.

## Headline numbers

Mean ms/op. `slope` is ms of added cost per ms of injected round-trip time — which, as the next
section shows, is just the arm's round-trip count per invocation.

| shape | arm | 0ms | 5ms | 20ms | 50ms | slope |
|---|---|---|---|---|---|---|
| shallow-narrow | Grackle | 8.8 | 21.1 | 52.6 | 115.4 | **2.13** |
| shallow-narrow | eager | 17.0 | 28.9 | 59.3 | 120.2 | **2.06** |
| shallow-narrow | naive | 227.5 | 577.7 | 1543.7 | 3485.9 | **65.17** |
| deep-narrow | Grackle | 44.0 | 57.2 | 86.9 | 147.8 | **2.08** |
| deep-narrow | eager | 74.6 | 86.0 | 118.7 | 173.4 | **1.98** |
| deep-narrow | naive | 875.6 | 2349.6 | 6506.2 | 14683.4 | **276.16** |
| deep-wide | Grackle | 88.3 | 98.6 | 129.6 | 186.9 | **1.97** |
| deep-wide | eager | 76.9 | 85.5 | 116.8 | 174.0 | **1.94** |
| deep-wide | naive | 871.5 | 2348.0 | 6499.5 | 14777.4 | **278.12** |
| untuned | Grackle | 61.1 | 75.4 | 103.8 | 164.5 | **2.07** |
| untuned | eager | 849.9 | 2261.6 | 6242.8 | 14062.7 | **264.26** |
| untuned | naive | 858.9 | 2283.4 | 6205.9 | 14116.3 | **265.15** |

For the deep shapes at 50ms, read Score/mean only — a 5s iteration completes roughly one
invocation there, so the percentile columns rest on about five samples.

## The slope is the round-trip count

Every arm runs inside a transaction, which costs one extra round trip per invocation: pgjdbc folds
`BEGIN` into the same flush as the first statement, so `COMMIT` is the only addition. An arm's
predicted slope is therefore **its statement count plus one**, and that is what came out:

| arm / shape | statements | predicted slope | measured | error |
|---|---|---|---|---|
| Grackle (all four shapes) | 1 | 2 | 1.97-2.13 | ≤ +7% |
| eager, three tuned shapes | 1 | 2 | 1.94-2.06 | ≤ +3% |
| eager, untuned | 261 | 262 | 264.26 | +0.9% |
| naive, shallow-narrow | 63 | 64 | 65.17 | +1.8% |
| naive, deep-narrow | 271 | 272 | 276.16 | +1.5% |
| naive, deep-wide | 272 | 273 | 278.12 | +1.9% |
| naive, untuned | 260 | 261 | 265.15 | +1.6% |

A model derived purely from statement counts predicts wall-clock behavior across three orders of
magnitude to within about 2%. The residual is consistently positive and small — the proxy hop and
per-invocation connection checkout.

This is the phase's methodological result: **the timing measurement and the query-count
measurement are the same measurement**, and each independently validates the other.

## The untuned shape

This is what the suite was built to show.

At 50ms RTT, the eager arm takes **14,063 ms** on the untuned shape against Grackle's **164 ms** —
a factor of **85**. The eager arm's carefully tuned entity graphs cover three query shapes; on the
fourth it falls back to the blanket `@BatchSize` default and behaves exactly like the naive arm
(264.26 slope against naive's 265.15, and the two are within 0.4% of each other at every latency
level).

The defensible claim is not "Grackle is faster than a well-configured ORM." It is that **the ORM's
good numbers are per-shape and have to be re-earned for every new query shape**, while Grackle
emits one statement by construction for arbitrary client-chosen shapes. In a GraphQL setting,
where clients choose the shape, "the shape nobody tuned for" is the normal case, not the edge case.

## Where Grackle does not win, stated plainly

On `deep-wide`, the eager arm beats Grackle at every latency level — 76.9 vs 88.3 ms at 0ms, 174.0
vs 186.9 at 50ms. The slopes are identical (1.94 vs 1.97), so this is not a round-trip difference:
it is a constant ~12ms of CPU, and it is the same field-breadth effect phase 2 documented. Grackle's
per-row response assembly scales with the number of leaf fields selected (`Option`/`Result`
wrapping per cell); Hibernate's does not, because it materializes the full row either way and
reading two more already-loaded fields is free.

Grackle wins `shallow-narrow` and `deep-narrow` at every level, loses `deep-wide` by a constant
margin, and wins `untuned` by ~85x.

## Fairness: the transaction asymmetry, and how it was caught

The first sweep ran the ORM arms in **autocommit** (no transaction anywhere in
`benchmarks-orm/src/main/scala`, HikariCP defaulting to `autoCommit = true`) while the Grackle arm
went through doobie's `transact`. That handed the ORM arms a one-round-trip advantage per
invocation — measured slopes of 0.91-1.02 for the eager arm against Grackle's ~2.0.

On the single-statement tuned shapes that is decisive. Under the asymmetric configuration the eager
arm beat Grackle on `deep-narrow` at 50ms (116.5 vs 146.8 ms); with both arms transactional, Grackle
wins (173.4 vs 147.8). **A configuration detail, not either system's SQL, decided who won.**

The fix wraps both ORM `@Benchmark` methods in a JPA resource-local transaction, inside the timed
region, mirroring the Spring `@Transactional(readOnly = true)` that is standard on read endpoints
and that the eager arm's design already mirrors elsewhere. Running one arm transactional and the
other not is the one configuration that cannot be defended.

Effect on the N+1 shapes was negligible, as predicted: naive slopes moved 274.11 to 276.16 and
263.02 to 265.15. An arm already issuing hundreds of statements does not notice one more.

## Flush mode and the commit-time dirty check — checked, not assumed

Spring's `@Transactional(readOnly = true)` sets Hibernate's flush mode to `MANUAL`, which skips the
dirty-check sweep over the persistence context at commit. This benchmark deliberately does **not**
do that, and the decision is measured rather than assumed.

The naive arm is where a dirty check would cost the most — it loads thousands of entities per
invocation, and with the transaction inside the timed region the check falls inside the
measurement. Introducing the transaction moved its zero-latency figures by:

| shape | autocommit | transactional | delta |
|---|---|---|---|
| shallow-narrow | 224.3 | 227.5 | +1.6% |
| deep-narrow | 860.6 | 875.6 | +1.7% |
| deep-wide | 853.3 | 871.5 | +2.2% |
| untuned | 844.4 | 858.9 | +1.7% |

**At or under this suite's run-to-run noise floor**, which the untouched Grackle arm independently
put at about 2% across the same pair of sweeps (its 0ms figures moved 8.9→8.8, 43.9→44.0,
86.4→88.3, 61.3→61.1). Part of even that delta is the commit round trip itself rather than flush
cost.

So commit-time dirty checking does not contribute meaningfully to any data point here. Suppressing
it would require an `unwrap(classOf[org.hibernate.Session])` call and a Hibernate-specific
dependency in exchange for nothing observable, and a plain transaction is the more faithful default
anyway — a non-read-only transaction pays that dirty check in production too. Revisit only if a
future change makes it measurable.

## Proxy hop cost — quantified

Every consumer of port 5433 now goes through Toxiproxy, so pre-phase-3 figures were measured
without that hop. Re-running `SqlJoinDepthBenchmark` through the proxy at zero injected latency,
against phase 1's pre-proxy p50:

| depth | phase 1 (direct) | phase 3 (via proxy) | delta |
|---|---|---|---|
| 2 | 5.2 | 5.61 | +0.41 (+7.9%) |
| 4 | 10.5 | 10.94 | +0.44 (+4.2%) |
| 6 | 21.9 | 22.22 | +0.32 (+1.5%) |
| 8 | 36.3 | 36.70 | +0.40 (+1.1%) |
| 10 | 43.3 | 43.12 | −0.18 (−0.4%) |

The delta is roughly **constant at ~+0.4ms per invocation, not proportional** — exactly what a
fixed per-round-trip hop looks like, since Grackle issues one statement regardless of depth. That
works out to ~0.2ms per round trip. It is a sub-percent effect on the deep queries and ~8% at
depth 2, so it matters for shallow absolute figures and not for the shape of any curve.

## Reproducing

    sbt benchPgUp
    sbt "benchmarksOrm/Jmh/run -rf json -rff results.json OrmVsGrackleBenchmark"

The trailing class filter is not optional: `benchmarksOrm` depends on `benchmarksSql`, so JMH
otherwise discovers and runs `SqlJoinDepthBenchmark` and `RawVsGrackleBenchmark` too, which carry
`@Fork(3)` and 10s iterations and roughly double the wall-clock time. Budget 40-50 minutes with the
filter.

## Caveats

- One machine (WSL2, shared/virtualized), one database, one dataset, one root country code (`FR`).
- `@Fork(1)` with 2 warmup + 5 measurement iterations is below this repo's publishable tier
  (`@Fork(3)`, 5+10 at 10s). The effects here are large enough that this trade is cheap, but a
  citable run should override on the command line.
- Injected latency is deterministic (jitter fixed at 0). Real networks are not.
- The ORM arms are one reasonable Hibernate configuration, not the best possible one. The claim
  rests on the untuned shape's behavior, which is a property of per-shape tuning as a strategy, not
  of any particular tuning effort.
