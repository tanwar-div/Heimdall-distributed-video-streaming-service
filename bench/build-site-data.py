#!/usr/bin/env python3
"""
Builds docs/data/benchmarks.json - the data behind the GitHub Pages showcase -
from the raw benchmark outputs in bench-results/.

The site renders every chart from this file rather than from numbers typed into
HTML, so re-running the benchmarks and this script is the whole update path and
the published figures cannot drift from what was measured.

    python3 bench/build-site-data.py
"""
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS = ROOT / "bench-results"
OUT = ROOT / "docs" / "data" / "benchmarks.json"


def num(cell):
    """'1.275x' -> 1.275, '63.73%' -> 63.73, '10.9 KiB' -> 11161.6 bytes, '96 B' -> 96."""
    cell = cell.strip().strip("`")
    m = re.fullmatch(r"([\d.,]+)\s*(KiB|B)", cell)
    if m:
        value = float(m.group(1).replace(",", ""))
        return value * 1024 if m.group(2) == "KiB" else value
    return float(cell.rstrip("x%").replace(",", ""))


def table_rows(block):
    for line in block.splitlines():
        if line.startswith("| `"):
            yield [c.strip() for c in line.strip().strip("|").split("|")]


def routing_quality():
    text = (RESULTS / "routing-quality.md").read_text()
    sections = re.split(r"^### (\d+) primaries.*$", text, flags=re.M)
    out = []
    for i in range(1, len(sections), 2):
        members = int(sections[i])
        for cells in table_rows(sections[i + 1]):
            out.append({
                "members": members,
                "algorithm": cells[0].strip("`"),
                "peakLoad": num(cells[1]),
                "minLoad": num(cells[2]),
                "coefficientOfVariation": num(cells[3]),
                "churnOnLossPct": num(cells[4]),
                "churnOnLossVsOptimal": num(cells[5]),
                "churnOnJoinPct": num(cells[6]),
                "churnOnJoinVsOptimal": num(cells[7]),
                "routingStateBytes": num(cells[8]),
            })
    return out


def end_to_end():
    text = (RESULTS / "e2e-results.md").read_text()
    size = re.search(r"Object: \*\*(\d+) MiB\*\*", text)
    seconds = re.search(r"(\d+)s measured per cell", text)
    sections = re.split(r"^### ([\w-]+)$", text, flags=re.M)
    rows = []
    for i in range(1, len(sections), 2):
        workload = sections[i]
        for c in table_rows(sections[i + 1]):
            rows.append({
                "workload": workload,
                "target": c[0].strip("`"),
                "concurrency": int(c[1]),
                "mibPerSecond": num(c[2]),
                "opsPerSecond": num(c[3]),
                "ttfbP50Ms": num(c[4]),
                "ttfbP99Ms": num(c[5]),
                "totalP50Ms": num(c[6]),
                "totalP99Ms": num(c[7]),
                "errors": int(c[8]),
                "integrityFailures": int(c[9]),
            })
    return {
        "objectMiB": int(size.group(1)) if size else None,
        "measureSeconds": int(seconds.group(1)) if seconds else None,
        "rows": rows,
    }


def jmh():
    entries = json.loads((RESULTS / "jmh-results.json").read_text())
    groups = {"routerLookup": [], "hashFunction": [], "membershipChange": [], "readPath": [], "chunking": []}
    meta = {}
    for e in entries:
        name = e["benchmark"].rsplit(".", 2)
        cls, method = name[-2], name[-1]
        params = e.get("params", {})
        pm = e["primaryMetric"]
        point = {"score": pm["score"], "error": pm["scoreError"], "unit": pm["scoreUnit"]}
        meta = {"jdk": e["jdkVersion"], "jmh": e["jmhVersion"], "forks": e["forks"],
                "warmup": f'{e["warmupIterations"]}x{e["warmupTime"]}',
                "measurement": f'{e["measurementIterations"]}x{e["measurementTime"]}'}
        if cls == "RouterLookupBenchmark":
            groups["routerLookup"].append({"algorithm": params["variant"], "members": int(params["members"]), **point})
        elif cls == "HashFunctionBenchmark":
            groups["hashFunction"].append({"function": params["function"], "inputBytes": int(params["inputBytes"]), **point})
        elif cls == "MembershipChangeBenchmark":
            groups["membershipChange"].append({"algorithm": params["variant"], "members": int(params["members"]), **point})
        elif cls == "ReadPathBenchmark":
            groups["readPath"].append({"operation": method, "replicas": int(params["replicasPerPrimary"]), **point})
        elif cls == "ChunkingBenchmark":
            chunk = int(params["chunkSizeBytes"])
            # Each op chunks a 32 MiB object, so ops/s x 32 is MiB/s.
            groups["chunking"].append({"chunkSizeBytes": chunk, "mibPerSecond": pm["score"] * 32,
                                       "mibPerSecondError": pm["scoreError"] * 32})
    return groups, meta


# Matches the page's own asset references: stylesheet, scripts and config.
ASSET_REF = re.compile(r'(src|href)="((?:assets/[^"?]+)|config\.js)(?:\?v=[0-9a-f]+)?"')


def stamp_assets():
    """
    Appends a content hash to every asset URL in docs/index.html.

    Browsers (and GitHub Pages, which serves with a ten-minute cache) otherwise
    keep running the previous JavaScript after an update, so the page shows new
    markup driven by old code. A changed file gets a changed URL, which no cache
    can serve stale.
    """
    index = ROOT / "docs" / "index.html"

    def versioned(match):
        attribute, path = match.group(1), match.group(2)
        digest = hashlib.sha256((ROOT / "docs" / path).read_bytes()).hexdigest()[:10]
        return f'{attribute}="{path}?v={digest}"'

    index.write_text(ASSET_REF.sub(versioned, index.read_text()))


def main():
    jmh_groups, jmh_meta = jmh()
    data = {
        "environment": {
            "cores": 12, "memoryGiB": 14, "storage": "NVMe", **jmh_meta,
            "note": "Everything - load generator included - ran on one machine. Compare targets, "
                    "not absolute figures.",
        },
        "routingQuality": routing_quality(),
        "throughput": jmh_groups,
        "endToEnd": end_to_end(),
        "tests": {
            "unitAndProperty": 96,
            "integration": 5,
            "before": 21,
            "bugsFound": [{
                "summary": "Unsatisfiable Range returned 206 with a negative Content-Length",
                "detail": "Spring's HttpRange clamps a range's end past end-of-file but not its start, so "
                          "bytes=999999999- produced start > end. Caught by the integration suite against real "
                          "processes; now a 416.",
            }],
        },
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, indent=2))
    stamp_assets()
    counts = {k: len(v) for k, v in jmh_groups.items()}
    print(f"wrote {OUT.relative_to(ROOT)}: {len(data['routingQuality'])} routing rows, "
          f"{len(data['endToEnd']['rows'])} e2e rows, jmh {counts}")


if __name__ == "__main__":
    main()
