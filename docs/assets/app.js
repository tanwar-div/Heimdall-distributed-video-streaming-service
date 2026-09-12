/*
 * The Heimdall showcase page.
 *
 * Benchmark sections render from data/benchmarks.json, which
 * bench/build-site-data.py generates from the raw benchmark output, so no
 * figure here is typed in by hand. The Demo section shows the recorded demo
 * from config.js.
 *
 * Opened with ?backend=<url>, the Demo section instead becomes a live view of a
 * running cluster - the video streamed through Heimdall, live counters, and
 * which node served each chunk - which is what the demo recording captures.
 */
(() => {
  "use strict";

  const SITE = window.HEIMDALL_SITE || {};
  const $ = (selector) => document.querySelector(selector);

  // ---- formatting ------------------------------------------------------
  const whole = new Intl.NumberFormat("en");
  const compact = new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 });

  const formatCount = (value) => (value < 10000 ? whole.format(Math.round(value)) : compact.format(value));

  function formatBytes(bytes) {
    const units = ["B", "KiB", "MiB", "GiB", "TiB"];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${unit === 0 || value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
  }

  function formatMs(ms) {
    if (ms === null || ms === undefined || Number.isNaN(ms)) return "—";
    if (ms < 10) return `${ms.toFixed(2)} ms`;
    if (ms < 100) return `${ms.toFixed(1)} ms`;
    return `${whole.format(Math.round(ms))} ms`;
  }

  function safeUrl(value) {
    if (!value) return "";
    try {
      const url = new URL(value, window.location.href);
      if (url.protocol !== "https:" && url.protocol !== "http:") return "";
      return url.href.replace(/\/+$/, "");
    } catch {
      return "";
    }
  }

  // ---- names and colours: a colour always follows the same entity -------
  const ALGORITHMS = {
    "rendezvous/murmur3": { name: "Rendezvous hashing", note: "current default", tone: "accent" },
    "ring/crc32/v100": { name: "Ring, CRC-32, 100 vnodes", note: "old default", tone: "series-2" },
    "jump/murmur3": { name: "Jump hash (Google)", tone: "series-3" },
    "ring/crc32/v500": { name: "Ring, CRC-32, 500 vnodes" },
    "ring/fnv1a64/v100": { name: "Ring, FNV-1a, 100 vnodes" },
    "ring/murmur3/v100": { name: "Ring, murmur3, 100 vnodes" },
    "ring/murmur3/v500": { name: "Ring, murmur3, 500 vnodes" },
    "ring/md5/v100": { name: "Ring, MD5, 100 vnodes" },
    "ketama/md5/p160": { name: "Ketama (memcached)" },
  };
  const describeAlgorithm = (id) => ALGORITHMS[id] || { name: id };
  const toneOf = (id) => describeAlgorithm(id).tone || "context";
  const ROUTING_LEGEND = [
    { label: "Rendezvous hashing (current default)", tone: "accent" },
    { label: "CRC-32 ring (old default)", tone: "series-2" },
    { label: "Jump hash", tone: "series-3" },
    { label: "Other algorithms", tone: "context" },
  ];

  const TARGETS = {
    heimdall: { name: "Heimdall", tone: "accent" },
    "minio-direct": { name: "MinIO, read directly", tone: "context" },
    seaweedfs: { name: "SeaweedFS", tone: "context" },
    nginx: { name: "nginx, static file", tone: "context" },
  };
  const TARGET_ORDER = ["heimdall", "minio-direct", "seaweedfs", "nginx"];
  const TARGET_LEGEND = [
    { label: "Heimdall", tone: "accent" },
    { label: "Other systems", tone: "context" },
  ];
  const WORKLOADS = {
    "startup-1MiB": "Starting playback (first 1 MiB)",
    "seek-4MiB": "Seeking (4 MiB from a random point)",
    "full-download": "Full download (64 MiB)",
  };

  // ---- small DOM helpers ------------------------------------------------
  function fillTiles(container, tiles) {
    const nodes = tiles.map((tile) => {
      const node = document.createElement("div");
      node.className = "tile";
      const label = document.createElement("p");
      label.className = "tile-label";
      label.textContent = tile.label;
      const value = document.createElement("p");
      value.className = "tile-value";
      value.textContent = tile.value;
      node.append(label, value);
      if (tile.note) {
        const note = document.createElement("p");
        note.className = "tile-note";
        note.textContent = tile.note;
        node.append(note);
      }
      return node;
    });
    container.replaceChildren(...nodes);
  }

  function note(container, text) {
    const paragraph = document.createElement("p");
    paragraph.className = "empty-note";
    paragraph.textContent = text;
    container.replaceChildren(paragraph);
  }

  /** One row of choices above the charts it scopes; every chart below re-renders against it. */
  function scopeControl(container, label, choices, initial, onChange) {
    container.className = "scope";
    const text = document.createElement("span");
    text.textContent = label;
    const group = document.createElement("div");
    group.className = "scope-buttons";
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", label);
    const buttons = choices.map((choice) => {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = choice.text;
      button.setAttribute("aria-pressed", String(choice.value === initial));
      button.addEventListener("click", () => {
        for (const other of buttons) other.setAttribute("aria-pressed", String(other === button));
        onChange(choice.value);
      });
      return button;
    });
    group.append(...buttons);
    container.replaceChildren(text, group);
    onChange(initial);
  }

  // ---- routing ----------------------------------------------------------
  function renderRouting(data) {
    const draw = (members) => {
      const rows = data.routingQuality.filter((row) => row.members === members);

      Charts.bars($("#chart-peak"),
        rows.map((row) => ({ label: describeAlgorithm(row.algorithm).name, value: Math.max(0, (row.peakLoad - 1) * 100), tone: toneOf(row.algorithm) }))
          .sort((a, b) => a.value - b.value),
        {
          title: "How overloaded the busiest primary is",
          caption: "How many more keys it holds than an even split would give it. 0% is perfect.",
          valueName: "Above an even split",
          format: (v) => `${v < 10 ? v.toFixed(1) : Math.round(v)}%`,
          tickFormat: (v) => `${Math.round(v)}%`,
          legend: ROUTING_LEGEND,
        });

      Charts.bars($("#chart-churn"),
        rows.map((row) => ({ label: describeAlgorithm(row.algorithm).name, value: row.churnOnLossVsOptimal, tone: toneOf(row.algorithm) }))
          .sort((a, b) => a.value - b.value),
        {
          title: "Keys moved when primary-0 fails",
          caption: "Compared with the fewest keys that could possibly move (1×). Anything above 1× is data moved for no reason.",
          valueName: "Times the minimum",
          format: (v) => `${v < 10 ? v.toFixed(2) : v.toFixed(1)}×`,
          tickFormat: (v) => `${Number.isInteger(v) ? v : v.toFixed(1)}×`,
          reference: { value: 1, label: "minimum" },
          legend: ROUTING_LEGEND,
        });

      const crc = rows.find((row) => row.algorithm === "ring/crc32/v100");
      const rendezvous = rows.find((row) => row.algorithm === "rendezvous/murmur3");
      if (crc && rendezvous) {
        const share = (row) => ((row.peakLoad / members) * 100).toFixed(1);
        $("#routing-summary").textContent =
          `With ${members} primaries, an even split gives each one ${(100 / members).toFixed(1)}% of all keys. ` +
          `The CRC-32 ring gave its busiest primary ${share(crc)}%. Rendezvous hashing: ${share(rendezvous)}%.`;
      }
    };

    scopeControl($("#routing-scope"), "Cluster size",
      [2, 3, 8, 32].map((m) => ({ value: m, text: m === 2 ? "2 primaries (deployed)" : `${m} primaries` })),
      2, draw);
  }

  function renderLookup(data) {
    const points = data.throughput.routerLookup;
    const xs = [...new Set(points.map((p) => p.members))].sort((a, b) => a - b);
    const order = ["rendezvous/murmur3", "ring/crc32/v100", "jump/murmur3",
      "ring/murmur3/v100", "ring/murmur3/v500", "ring/md5/v100", "ketama/md5/p160"];
    const series = order
      .filter((id) => points.some((p) => p.algorithm === id))
      .map((id) => ({
        name: describeAlgorithm(id).name,
        tone: toneOf(id),
        values: xs.map((x) => points.find((p) => p.algorithm === id && p.members === x)?.score ?? 0),
      }));

    Charts.lines($("#chart-lookup"), xs, series, {
      title: "Time to route one request as the cluster grows",
      caption: "Nanoseconds per routing decision, measured with JMH. Lower is better. Rendezvous is fastest up to about 32 primaries; past that, the ring's tree lookup wins.",
      xName: "Primaries",
      xLabel: "Primaries in the cluster",
      formatX: (x) => String(x),
      format: (v) => `${Math.round(v)} ns`,
      contextName: "Other ring variants",
    });
  }

  // ---- end to end -------------------------------------------------------
  function renderEndToEnd(data) {
    const rows = data.endToEnd.rows;
    const at = (workload, target, concurrency) =>
      rows.find((row) => row.workload === workload && row.target === target && row.concurrency === concurrency);

    // Headline ratios are computed from the data, never typed in.
    const heimdallStart = at("startup-1MiB", "heimdall", 1);
    const minioStart = at("startup-1MiB", "minio-direct", 1);
    const heimdallFull = at("full-download", "heimdall", 1);
    const minioFull = at("full-download", "minio-direct", 1);
    if (heimdallStart && minioStart && heimdallFull && minioFull) {
      const clean = rows.filter((row) => row.errors === 0 && row.integrityFailures === 0).length;
      fillTiles($("#e2e-tiles"), [
        { label: "Slower than MinIO on a 1 MiB read", value: `${(minioStart.mibPerSecond / heimdallStart.mibPerSecond).toFixed(1)}×`, note: "per-request overhead dominates" },
        { label: `Slower than MinIO on the full ${data.endToEnd.objectMiB} MiB`, value: `${(minioFull.mibPerSecond / heimdallFull.mibPerSecond).toFixed(1)}×`, note: "the gap narrows as reads grow" },
        { label: "Longer to first byte than MinIO", value: `${(heimdallStart.ttfbP50Ms / minioStart.ttfbP50Ms).toFixed(1)}×`, note: "median, when playback starts" },
        { label: "Runs with every byte correct", value: `${clean} of ${rows.length}`, note: "all four systems, every run" },
      ]);
    }

    const draw = (concurrency) => {
      const throughputRows = [];
      for (const workload of Object.keys(WORKLOADS)) {
        for (const target of TARGET_ORDER) {
          const row = at(workload, target, concurrency);
          if (row) throughputRows.push({ group: WORKLOADS[workload], label: TARGETS[target].name, value: row.mibPerSecond, tone: TARGETS[target].tone });
        }
      }
      Charts.bars($("#chart-throughput"), throughputRows, {
        title: "Throughput",
        caption: `Same ${data.endToEnd.objectMiB} MiB object, identical HTTP requests, ${concurrency} concurrent client${concurrency === 1 ? "" : "s"}. Higher is better.`,
        valueName: "MiB/s",
        format: (v) => `${whole.format(Math.round(v))} MiB/s`,
        tickFormat: (v) => whole.format(Math.round(v)),
        labelWidth: 180,
        valueWidth: 96,
        legend: TARGET_LEGEND,
      });

      const ttfbRows = TARGET_ORDER
        .map((target) => at("startup-1MiB", target, concurrency))
        .filter(Boolean)
        .map((row) => ({ label: TARGETS[row.target].name, value: row.ttfbP50Ms, tone: TARGETS[row.target].tone }));
      Charts.bars($("#chart-ttfb"), ttfbRows, {
        title: "Time to first byte",
        caption: "Median wait before the first byte arrives when a video starts. Lower is better.",
        valueName: "Median",
        format: formatMs,
        tickFormat: (v) => `${v < 10 ? v.toFixed(1) : Math.round(v)} ms`,
        labelWidth: 150,
        legend: TARGET_LEGEND,
      });
    };

    scopeControl($("#e2e-scope"), "Concurrent clients",
      [1, 8, 32].map((c) => ({ value: c, text: String(c) })), 1, draw);
  }

  // ---- testing ----------------------------------------------------------
  function renderTests(data) {
    const tests = data.tests;
    fillTiles($("#test-tiles"), [
      { label: "Unit and property-based tests", value: whole.format(tests.unitAndProperty), note: `up from ${tests.before}` },
      { label: "End-to-end tests", value: whole.format(tests.integration), note: "real MinIO, real processes" },
      { label: "Routing algorithms held to the same guarantees", value: "6", note: "checked on generated inputs" },
      { label: "Real bugs caught", value: whole.format(tests.bugsFound.length), note: "by the end-to-end tests" },
    ]);
    const bug = tests.bugsFound[0];
    if (bug) {
      $("#bug-summary").textContent = bug.summary;
      $("#bug-detail").textContent = bug.detail;
    }
  }

  // ---- demo video -------------------------------------------------------
  /** A YouTube or Vimeo link turned into its embeddable player URL, or "" for anything else. */
  function embedUrl(src) {
    let url;
    try {
      url = new URL(src);
    } catch {
      return "";
    }
    const host = url.hostname.replace(/^(www|m)\./, "");
    let youtube = "";
    if (host === "youtu.be") {
      youtube = url.pathname.slice(1);
    } else if (host === "youtube.com" || host === "youtube-nocookie.com") {
      youtube = url.searchParams.get("v") || (url.pathname.match(/^\/(?:embed|shorts|live)\/([\w-]+)/) || [])[1] || "";
    }
    if (/^[\w-]{6,20}$/.test(youtube)) return `https://www.youtube-nocookie.com/embed/${youtube}?rel=0`;
    if (host === "vimeo.com" || host === "player.vimeo.com") {
      const vimeo = (url.pathname.match(/(\d{6,12})/) || [])[1];
      if (vimeo) return `https://player.vimeo.com/video/${vimeo}`;
    }
    return "";
  }

  /** A still or animated image (GIF, PNG, WebP, AVIF, JPEG) rather than a video file. */
  const IMAGE_FILE = /\.(gif|png|jpe?g|webp|avif)(\?|#|$)/i;

  function renderDemoVideo(frame, source, poster, alt) {
    const src = safeUrl(source);
    if (!src) {
      const empty = document.createElement("div");
      empty.className = "video-offline";
      const title = document.createElement("strong");
      title.textContent = "Demo video coming soon";
      const text = document.createElement("span");
      text.textContent = "A recording of Heimdall streaming, balancing reads, and recovering from a stopped node.";
      empty.append(title, text);
      frame.replaceChildren(empty);
      console.info("Heimdall showcase: set demoVideo in config.js to show the demo recording.");
      return;
    }

    if (IMAGE_FILE.test(src)) {
      const image = document.createElement("img");
      image.src = src;
      image.alt = alt || "Heimdall streaming a video while reads spread across replicas.";
      // The hero image is the first thing on the page, so it loads eagerly and
      // early rather than waiting for a lazy-load pass.
      image.fetchPriority = "high";
      image.decoding = "async";
      frame.replaceChildren(image);
      return;
    }

    const embed = embedUrl(src);
    if (embed) {
      const iframe = document.createElement("iframe");
      iframe.src = embed;
      iframe.title = "Heimdall demo";
      iframe.loading = "lazy";
      iframe.allow = "autoplay; encrypted-media; picture-in-picture; fullscreen";
      iframe.referrerPolicy = "strict-origin-when-cross-origin";
      iframe.allowFullscreen = true;
      frame.replaceChildren(iframe);
      return;
    }

    const video = document.createElement("video");
    video.controls = true;
    video.preload = "metadata";
    video.playsInline = true;
    video.src = src;
    const posterSrc = safeUrl(poster);
    if (posterSrc) video.poster = posterSrc;
    frame.replaceChildren(video);
  }

  // ---- live cluster -----------------------------------------------------
  const LIVE_TILE_LABELS = ["Reads served", "Chunks fetched", "Video data served", "Failovers",
    "Chunk fetch, median", "Chunk fetch, 99th percentile"];

  function startLive(backend, objectId) {
    const pill = $("#live-status");
    const video = $("#live-video");
    const offline = $("#live-offline");
    const offlineMessage = $("#live-offline-message");
    let previous = null;
    let connected = false;
    let failures = 0;

    const setPill = (tone, icon, text) => {
      const iconNode = document.createElement("span");
      iconNode.className = `status-icon is-${tone}`;
      iconNode.textContent = icon;
      iconNode.setAttribute("aria-hidden", "true");
      const textNode = document.createElement("span");
      textNode.textContent = text;
      pill.replaceChildren(iconNode, textNode);
    };

    const showOffline = (message) => {
      connected = false;
      previous = null;
      setPill("muted", "○", "Cluster not reachable");
      if (video.getAttribute("src")) {
        video.pause();
        video.removeAttribute("src");
        video.load();
      }
      video.hidden = true;
      offline.hidden = false;
      offlineMessage.textContent = message;
      fillTiles($("#live-tiles"), LIVE_TILE_LABELS.map((label) => ({ label, value: "—" })));
      $("#live-balance").textContent = "";
      const list = $("#live-nodes");
      list.dataset.layout = "";
      note(list, "Per-node traffic appears here while the cluster is running.");
    };

    fillTiles($("#live-tiles"), LIVE_TILE_LABELS.map((label) => ({ label, value: "—" })));
    setPill("muted", "…", `Connecting to ${backend}`);

    const poll = async () => {
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 5000);
      try {
        const response = await fetch(`${backend}/metrics/live`, { cache: "no-store", signal: controller.signal });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const live = await response.json();
        failures = 0;
        if (!connected) {
          connected = true;
          offline.hidden = true;
          video.hidden = false;
          // readPercent=100 reads from every replica rather than the default
          // half. With two replicas per shard, half means one replica per read,
          // and a single playback could not show chunks spreading across nodes.
          if (!video.getAttribute("src")) video.src = `${backend}/objects/${encodeURIComponent(objectId)}?readPercent=100`;
        }
        renderLive(live, previous, setPill);
        previous = { live, at: Date.now() };
      } catch (error) {
        failures += 1;
        // One slow poll is noise; two in a row once connected means the cluster is gone.
        if (connected ? failures >= 2 : failures === 1) {
          showOffline(connected
            ? `Lost contact with the cluster at ${backend}.`
            : `Couldn't reach a cluster at ${backend}. Is it running, with a video uploaded as "${objectId}"?`);
        }
      } finally {
        window.clearTimeout(timeout);
        // Background tabs poll rarely: every open tab costs the demo VM requests.
        window.setTimeout(poll, document.hidden ? 15000 : 2000);
      }
    };
    poll();
  }

  function renderLive(live, previous, setPill) {
    const nodes = live.nodes || [];
    const up = nodes.filter((node) => node.status === "UP").length;
    if (nodes.length && up === nodes.length) {
      setPill("good", "✓", `Live cluster · all ${nodes.length} storage nodes up`);
    } else {
      setPill("serious", "!", `Live · ${up} of ${nodes.length} storage nodes up, reads are failing over`);
    }

    const latency = live.chunkFetchLatency || {};
    // Percentiles cover the last minute only, so a quiet minute has none.
    const windowNote = latency.p50Ms == null ? "no reads in the last minute" : "over the last minute";
    fillTiles($("#live-tiles"), [
      { label: "Reads served", value: formatCount(live.totals.reads) },
      { label: "Chunks fetched", value: formatCount(live.totals.chunksServed) },
      { label: "Video data served", value: formatBytes(live.totals.bytesServed) },
      { label: "Failovers", value: formatCount(live.totals.failovers), note: "chunks a fallback node answered" },
      { label: "Chunk fetch, median", value: formatMs(latency.p50Ms), note: windowNote },
      { label: "Chunk fetch, 99th percentile", value: formatMs(latency.p99Ms), note: windowNote },
    ]);

    const list = $("#live-nodes");
    const layout = nodes.map((node) => node.id).join(",");
    if (list.dataset.layout !== layout) buildNodeRows(list, nodes);
    list.dataset.layout = layout;

    const seconds = previous ? (Date.now() - previous.at) / 1000 : 0;
    const before = new Map((previous ? previous.live.nodes : []).map((node) => [node.id, node]));
    const max = Math.max(1, ...nodes.map((node) => node.chunksServed));

    for (const node of nodes) {
      const row = list.querySelector(`[data-node="${CSS.escape(node.id)}"]`);
      if (!row) continue;
      const isUp = node.status === "UP";
      row.classList.toggle("is-down", !isUp);
      const icon = row.querySelector(".status-icon");
      icon.className = `status-icon ${isUp ? "is-good" : "is-critical"}`;
      icon.textContent = isUp ? "✓" : "✕";
      row.querySelector(".node-state").textContent = isUp ? "up" : "down";
      row.querySelector(".node-bar").style.width = `${(node.chunksServed / max) * 100}%`;
      const prior = before.get(node.id);
      const rate = prior && seconds > 0 ? (node.chunksServed - prior.chunksServed) / seconds : 0;
      row.querySelector(".node-count").textContent = `${formatCount(node.chunksServed)} chunks · ${formatBytes(node.bytesServed)}`;
      row.querySelector(".node-rate").textContent = rate >= 0.1 ? `  +${rate < 10 ? rate.toFixed(1) : Math.round(rate)}/s` : "";
      row.heimdallNode = node;
    }
    $("#live-balance").textContent = balanceSummary(nodes);
  }

  function buildNodeRows(list, nodes) {
    const children = [];
    let group = null;
    for (const node of nodes) {
      if (node.primaryId !== group) {
        group = node.primaryId;
        const heading = document.createElement("div");
        heading.className = "node-group";
        heading.textContent = `Shard ${group}`;
        children.push(heading);
      }
      const row = document.createElement("div");
      row.className = "node-row";
      row.dataset.node = node.id;
      row.tabIndex = 0;

      const name = document.createElement("div");
      name.className = "node-name";
      const icon = document.createElement("span");
      icon.className = "status-icon";
      icon.setAttribute("aria-hidden", "true");
      const label = document.createElement("span");
      label.className = "node-label";
      label.textContent = node.role === "PRIMARY" ? `${node.id} (primary)` : node.id.replace(`${node.primaryId}-`, "");
      const state = document.createElement("span");
      state.className = "node-state";
      name.append(icon, label, state);

      const track = document.createElement("div");
      track.className = "node-track";
      const bar = document.createElement("div");
      bar.className = "node-bar";
      track.append(bar);

      const value = document.createElement("div");
      value.className = "node-value";
      const count = document.createElement("span");
      count.className = "node-count";
      const rate = document.createElement("span");
      rate.className = "node-rate";
      value.append(count, rate);

      row.append(name, track, value);

      const show = (event) => {
        const data = row.heimdallNode;
        if (!data) return;
        const box = row.getBoundingClientRect();
        const latency = data.latency || {};
        Charts.showTip(event.clientX ?? box.left + box.width / 2, event.clientY ?? box.top, [
          { value: `${formatCount(data.chunksServed)} chunks`, label: data.id, tone: "accent" },
          { value: formatBytes(data.bytesServed), label: "served" },
          { value: formatMs(latency.p50Ms), label: "median fetch, last minute" },
          { value: formatCount(data.chunkFailures), label: "failed fetches" },
        ]);
      };
      row.addEventListener("pointermove", show);
      row.addEventListener("focus", show);
      row.addEventListener("pointerleave", Charts.hideTip);
      row.addEventListener("blur", Charts.hideTip);
      children.push(row);
    }
    list.replaceChildren(...children);
  }

  /** One sentence on whether reads are actually spreading, for each shard that has seen traffic. */
  function balanceSummary(nodes) {
    const shards = new Map();
    for (const node of nodes) {
      if (!shards.has(node.primaryId)) shards.set(node.primaryId, []);
      shards.get(node.primaryId).push(node);
    }
    const parts = [];
    for (const [primaryId, members] of shards) {
      const total = members.reduce((sum, n) => sum + n.chunksServed, 0);
      if (total === 0) continue;
      const replicas = members.filter((n) => n.role === "REPLICA");
      const replicaShare = Math.round((replicas.reduce((sum, n) => sum + n.chunksServed, 0) / total) * 100);
      const split = replicas.map((n) => `${Math.round((n.chunksServed / total) * 100)}%`).join(" and ");
      parts.push(`In ${primaryId}, replicas answered ${replicaShare}% of chunk fetches, split ${split}`);
    }
    if (!parts.length) return "No chunks served yet. Press play on the video.";
    return `${parts.join(". ")}. The primary only answers when a replica can't.`;
  }

  // ---- start ------------------------------------------------------------
  async function main() {
    const repo = safeUrl(SITE.repoUrl);
    const branchPath = String(SITE.branch || "main").split("/").map(encodeURIComponent).join("/");
    if (repo) {
      for (const link of document.querySelectorAll("[data-repo-link]")) link.href = repo;
      for (const link of document.querySelectorAll("[data-report-link]")) link.href = `${repo}/blob/${branchPath}/BENCHMARKS.md`;
    }

    const backend = safeUrl(new URLSearchParams(window.location.search).get("backend"));
    if (backend) {
      // Live mode: the hero's recorded video gives way to the running cluster.
      $("#hero-media").hidden = true;
      $("#live-section").hidden = false;
      $("#live-status").hidden = false;
      startLive(backend, SITE.videoObjectId || "showcase.mp4");
    } else {
      renderDemoVideo($("#hero-media .video-frame"), SITE.demoVideo, SITE.demoPoster, SITE.demoAlt);
    }

    try {
      const response = await fetch("data/benchmarks.json", { cache: "no-cache" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      renderRouting(data);
      renderLookup(data);
      renderEndToEnd(data);
      renderTests(data);
    } catch (error) {
      for (const id of ["#chart-peak", "#chart-churn", "#chart-lookup", "#chart-throughput", "#chart-ttfb"]) {
        note($(id), "The benchmark data couldn't be loaded.");
      }
      console.error("benchmark data", error);
    }
  }

  main();
})();
