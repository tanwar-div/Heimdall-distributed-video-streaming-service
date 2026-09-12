/*
 * A small SVG chart kit for the Heimdall showcase.
 *
 * Hand-written instead of a charting library so every mark follows one spec:
 * thin bars with a rounded data end and a square baseline, 2px lines, solid
 * hairline gridlines, text in ink colours rather than series colours, a hover
 * and keyboard tooltip on every mark, and a table view behind every chart so
 * no number is only reachable by hovering.
 */
const Charts = (() => {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";

  function svgEl(name, attrs, parent) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attrs || {})) node.setAttribute(key, String(value));
    if (parent) parent.appendChild(node);
    return node;
  }

  function htmlEl(name, className, text, parent) {
    const node = document.createElement(name);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    if (parent) parent.appendChild(node);
    return node;
  }

  // ---- tooltip: one shared node, filled with textContent only ----------
  let tipNode = null;

  function showTip(clientX, clientY, rows) {
    if (!tipNode) {
      tipNode = htmlEl("div", "chart-tip", null, document.body);
      tipNode.setAttribute("role", "tooltip");
    }
    tipNode.replaceChildren();
    for (const row of rows) {
      const line = htmlEl("div", "chart-tip-row", null, tipNode);
      if (row.tone) htmlEl("span", `chart-tip-key tone-${row.tone}`, null, line).setAttribute("aria-hidden", "true");
      htmlEl("strong", null, row.value, line);
      htmlEl("span", null, row.label, line);
    }
    tipNode.hidden = false;
    const gap = 14;
    const width = tipNode.offsetWidth;
    const height = tipNode.offsetHeight;
    let left = clientX + gap;
    let top = clientY + gap;
    if (left + width > window.innerWidth - 8) left = clientX - width - gap;
    if (top + height > window.innerHeight - 8) top = clientY - height - gap;
    tipNode.style.left = `${Math.max(8, left)}px`;
    tipNode.style.top = `${Math.max(8, top)}px`;
  }

  function hideTip() {
    if (tipNode) tipNode.hidden = true;
  }

  // ---- shared helpers --------------------------------------------------
  function niceMax(value) {
    if (!(value > 0)) return 1;
    const exponent = 10 ** Math.floor(Math.log10(value));
    const fraction = value / exponent;
    const nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10;
    return nice * exponent;
  }

  /** Shortens an SVG label to fit, keeping the full text in a <title> (and in the table view). */
  function fitText(node, maxWidth) {
    const full = node.textContent;
    if (maxWidth <= 0 || node.getComputedTextLength() <= maxWidth) return;
    let text = full;
    while (text.length > 1 && node.getComputedTextLength() > maxWidth) {
      text = text.slice(0, -1);
      node.textContent = `${text.trimEnd()}…`;
    }
    svgEl("title", {}, node).textContent = full;
  }

  /**
   * Redraws when the container's width changes, and only then: drawing changes
   * the height, and reacting to height would loop.
   */
  function onWidthChange(container, node, draw) {
    if (container._chartObserver) container._chartObserver.disconnect();
    let lastWidth = Math.round(node.clientWidth);
    let frame = 0;
    const observer = new ResizeObserver((entries) => {
      const width = Math.round(entries[0].contentRect.width);
      if (width === 0 || width === lastWidth) return;
      lastWidth = width;
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(draw);
    });
    observer.observe(node);
    container._chartObserver = observer;
  }

  /** Title, caption, plot area, legend and the table-view twin every chart carries. */
  function frame(container, { title, caption }) {
    container.replaceChildren();
    container.classList.add("chart");
    const head = htmlEl("div", "chart-head", null, container);
    htmlEl("h3", "chart-title", title, head);
    const toggle = htmlEl("button", "chart-toggle", "Table", head);
    toggle.type = "button";
    toggle.setAttribute("aria-pressed", "false");
    if (caption) htmlEl("p", "chart-caption", caption, container);
    const plot = htmlEl("div", "chart-plot", null, container);
    const legend = htmlEl("div", "chart-legend", null, container);
    legend.hidden = true;
    const tableWrap = htmlEl("div", "chart-table-wrap", null, container);
    tableWrap.hidden = true;
    const table = htmlEl("table", "chart-table", null, tableWrap);

    toggle.addEventListener("click", () => {
      const showTable = tableWrap.hidden;
      tableWrap.hidden = !showTable;
      plot.hidden = showTable;
      legend.hidden = showTable || legend.childElementCount < 2;
      toggle.setAttribute("aria-pressed", String(showTable));
      toggle.textContent = showTable ? "Chart" : "Table";
    });
    return { plot, legend, table };
  }

  function fillTable(table, headers, rows) {
    table.replaceChildren();
    const headRow = htmlEl("tr", null, null, htmlEl("thead", null, null, table));
    for (const header of headers) htmlEl("th", null, header, headRow).setAttribute("scope", "col");
    const body = htmlEl("tbody", null, null, table);
    for (const cells of rows) {
      const tr = htmlEl("tr", null, null, body);
      cells.forEach((cell, i) => {
        const node = htmlEl(i === 0 ? "th" : "td", null, cell, tr);
        if (i === 0) node.setAttribute("scope", "row");
      });
    }
  }

  function fillLegend(legend, items) {
    legend.replaceChildren();
    for (const item of items) {
      const entry = htmlEl("span", "legend-item", null, legend);
      htmlEl("span", `legend-key ${item.kind || "rect"} tone-${item.tone}`, null, entry).setAttribute("aria-hidden", "true");
      htmlEl("span", null, item.label, entry);
    }
    legend.hidden = items.length < 2; // one series needs no legend: the title names it
  }

  /** A bar path with a 4px rounded data end and a square baseline. */
  function barPath(x, y, width, height, radius) {
    if (width <= 0.5) return "";
    const r = Math.min(radius, width, height / 2);
    return `M${x},${y}H${x + width - r}Q${x + width},${y} ${x + width},${y + r}` +
      `V${y + height - r}Q${x + width},${y + height} ${x + width - r},${y + height}H${x}Z`;
  }

  // ---- horizontal bars -------------------------------------------------
  /**
   * rows:    [{ label, value, tone, display?, group? }]
   * options: { title, caption, valueName, format, tickFormat?, max?, labelWidth?,
   *            reference?: { value, label }, legend?: [{ label, tone }] }
   */
  function bars(container, rows, options) {
    const { plot, legend, table } = frame(container, options);
    const format = options.format;
    const tickFormat = options.tickFormat || format;
    const display = (row) => row.display ?? format(row.value);
    const fullLabel = (row) => (row.group ? `${row.group}: ${row.label}` : row.label);

    fillTable(table, ["", options.valueName], rows.map((row) => [fullLabel(row), display(row)]));
    fillLegend(legend, options.legend || []);

    const draw = () => {
      plot.replaceChildren();
      const width = Math.max(300, Math.round(plot.clientWidth));
      const narrow = width < 560;
      // On narrow screens each label sits on its own line above its bar. A
      // side column there leaves ~130px per name, which cuts similar names
      // ("Ring, CRC-32, 100 vnodes" / "..., 500 vnodes") down to the same prefix.
      const stacked = narrow;
      const labelWidth = stacked ? 0 : options.labelWidth || 230;
      const valueWidth = options.valueWidth || 84;
      const rowHeight = stacked ? 46 : 30;
      const groupHeight = 28;
      const thickness = 14;
      const top = options.reference ? 22 : 6;
      const axisBand = 26;

      const items = [];
      let y = top;
      let currentGroup;
      for (const row of rows) {
        if (row.group !== undefined && row.group !== currentGroup) {
          currentGroup = row.group;
          items.push({ group: row.group, y });
          y += groupHeight;
        }
        items.push({ row, y });
        y += rowHeight;
      }
      const plotBottom = y;
      const height = plotBottom + axisBand;
      const x0 = stacked ? 0 : labelWidth + 10;
      const x1 = width - valueWidth;
      const dataMax = Math.max(...rows.map((row) => row.value), options.reference ? options.reference.value : 0);
      const max = options.max ?? niceMax(dataMax);
      const sx = (value) => x0 + (Math.max(0, value) / max) * (x1 - x0);

      const svg = svgEl("svg", { viewBox: `0 0 ${width} ${height}`, width, height, role: "img", "aria-label": options.title }, plot);

      const tickCount = narrow ? 2 : 4;
      for (let i = 0; i <= tickCount; i++) {
        const value = (max / tickCount) * i;
        svgEl("line", { x1: sx(value), x2: sx(value), y1: top, y2: plotBottom, class: i === 0 ? "axis-line" : "grid-line" }, svg);
        const anchor = i === 0 ? "start" : i === tickCount ? "end" : "middle";
        svgEl("text", { x: sx(value), y: plotBottom + 17, class: "tick", "text-anchor": anchor }, svg).textContent = tickFormat(value);
      }

      if (options.reference) {
        const rx = sx(options.reference.value);
        svgEl("line", { x1: rx, x2: rx, y1: top - 6, y2: plotBottom, class: "reference-line" }, svg);
        svgEl("text", { x: rx + 5, y: top - 9, class: "reference-label" }, svg).textContent = options.reference.label;
      }

      for (const item of items) {
        if (item.group !== undefined) {
          const groupLabel = svgEl("text", { x: 0, y: item.y + 19, class: "group-label" }, svg);
          groupLabel.textContent = item.group;
          fitText(groupLabel, width - 8);
          continue;
        }
        const { row } = item;
        const cy = stacked ? item.y + 31 : item.y + rowHeight / 2;
        const labelClass = row.tone === "context" ? "bar-label is-context" : "bar-label";
        const label = svgEl("text", stacked
          ? { x: 0, y: item.y + 15, class: labelClass }
          : { x: labelWidth, y: cy + 4, "text-anchor": "end", class: labelClass }, svg);
        label.textContent = row.label;
        fitText(label, stacked ? width - 8 : labelWidth - 4);

        const barWidth = Math.max(0, sx(row.value) - x0);
        const bar = svgEl("path", { d: barPath(x0, cy - thickness / 2, barWidth, thickness, 4), class: `bar tone-${row.tone}` }, svg);
        svgEl("text", { x: x0 + barWidth + 6, y: cy + 4, class: "bar-value" }, svg).textContent = display(row);

        // The whole row is the hit target, far larger than the 14px bar.
        const hit = svgEl("rect", {
          x: 0, y: item.y, width, height: rowHeight, class: "hit", tabindex: 0,
          role: "img", "aria-label": `${fullLabel(row)}: ${display(row)}`,
        }, svg);
        const show = (event) => {
          const box = hit.getBoundingClientRect();
          bar.classList.add("is-hover");
          const x = event.clientX ?? box.left + (x0 / width) * box.width;
          const yPos = event.clientY ?? box.top + box.height / 2;
          showTip(x, yPos, [{ value: display(row), label: fullLabel(row), tone: row.tone }]);
        };
        const hide = () => {
          bar.classList.remove("is-hover");
          hideTip();
        };
        hit.addEventListener("pointermove", show);
        hit.addEventListener("focus", show);
        hit.addEventListener("pointerleave", hide);
        hit.addEventListener("blur", hide);
      }
    };

    draw();
    onWidthChange(container, plot, draw);
  }

  // ---- lines -----------------------------------------------------------
  /**
   * xs are drawn evenly spaced - the benchmark's cluster sizes are powers of four.
   * series:  [{ name, values, tone }]; tone "context" draws a quiet gray line without markers.
   * options: { title, caption, xName, xLabel, format, formatX, contextName, height? }
   */
  function lines(container, xs, series, options) {
    const { plot, legend, table } = frame(container, options);
    const highlighted = series.filter((s) => s.tone !== "context");
    const context = series.filter((s) => s.tone === "context");

    fillTable(table, [options.xName, ...series.map((s) => s.name)],
      xs.map((x, i) => [options.formatX(x), ...series.map((s) => options.format(s.values[i]))]));
    fillLegend(legend, [
      ...highlighted.map((s) => ({ label: s.name, tone: s.tone, kind: "line" })),
      ...(context.length ? [{ label: options.contextName || "Others", tone: "context", kind: "line" }] : []),
    ]);

    const draw = () => {
      plot.replaceChildren();
      const width = Math.max(300, Math.round(plot.clientWidth));
      const endLabels = width >= 640;
      const margin = { top: 14, right: endLabels ? 176 : 18, bottom: 44, left: 58 };
      const height = options.height || 300;
      const innerW = width - margin.left - margin.right;
      const innerH = height - margin.top - margin.bottom;
      const max = niceMax(Math.max(...series.flatMap((s) => s.values)));
      const sx = (i) => margin.left + (xs.length === 1 ? innerW / 2 : (innerW * i) / (xs.length - 1));
      const sy = (value) => margin.top + innerH - (value / max) * innerH;

      const svg = svgEl("svg", { viewBox: `0 0 ${width} ${height}`, width, height, role: "img", "aria-label": options.title }, plot);

      const yTicks = 4;
      for (let i = 0; i <= yTicks; i++) {
        const value = (max / yTicks) * i;
        svgEl("line", { x1: margin.left, x2: margin.left + innerW, y1: sy(value), y2: sy(value), class: i === 0 ? "axis-line" : "grid-line" }, svg);
        svgEl("text", { x: margin.left - 8, y: sy(value) + 4, class: "tick", "text-anchor": "end" }, svg).textContent = options.format(value);
      }
      xs.forEach((x, i) => {
        svgEl("text", { x: sx(i), y: margin.top + innerH + 18, class: "tick", "text-anchor": "middle" }, svg).textContent = options.formatX(x);
      });
      if (options.xLabel) {
        svgEl("text", { x: margin.left + innerW / 2, y: height - 4, class: "axis-title", "text-anchor": "middle" }, svg).textContent = options.xLabel;
      }

      const pathFor = (values) => values.map((v, i) => `${i ? "L" : "M"}${sx(i)},${sy(v)}`).join("");
      for (const s of context) svgEl("path", { d: pathFor(s.values), class: "series-line tone-context" }, svg);
      for (const s of highlighted) svgEl("path", { d: pathFor(s.values), class: `series-line tone-${s.tone}` }, svg);
      for (const s of highlighted) {
        s.values.forEach((v, i) => svgEl("circle", { cx: sx(i), cy: sy(v), r: 4, class: `series-marker tone-${s.tone}` }, svg));
      }

      // Direct end labels only when they separate cleanly; otherwise the legend carries identity.
      if (endLabels) {
        const last = xs.length - 1;
        const placed = highlighted.map((s) => ({ s, y: sy(s.values[last]) })).sort((a, b) => a.y - b.y);
        const collides = placed.some((p, i) => i > 0 && p.y - placed[i - 1].y < 16);
        if (!collides) {
          for (const { s, y } of placed) {
            const x = sx(last) + 12;
            svgEl("line", { x1: x, x2: x + 12, y1: y, y2: y, class: `end-key tone-${s.tone}` }, svg);
            const label = svgEl("text", { x: x + 18, y: y + 4, class: "end-label" }, svg);
            label.textContent = s.name;
            fitText(label, margin.right - 34);
          }
        }
      }

      const crosshair = svgEl("line", { x1: 0, x2: 0, y1: margin.top, y2: margin.top + innerH, class: "crosshair" }, svg);
      crosshair.style.visibility = "hidden";
      const overlay = svgEl("rect", {
        x: margin.left - 24, y: margin.top, width: innerW + 48, height: innerH, class: "hit", tabindex: 0,
        role: "img", "aria-label": `${options.title}. Use the left and right arrow keys to read values.`,
      }, svg);

      let index = xs.length - 1;
      const showAt = (i, clientX, clientY) => {
        index = Math.max(0, Math.min(xs.length - 1, i));
        crosshair.setAttribute("x1", sx(index));
        crosshair.setAttribute("x2", sx(index));
        crosshair.style.visibility = "visible";
        const rows = [...highlighted, ...context].map((s) => ({ value: options.format(s.values[index]), label: s.name, tone: s.tone }));
        rows.unshift({ value: options.formatX(xs[index]), label: options.xName });
        showTip(clientX, clientY, rows);
      };
      const toClient = (i) => {
        const box = svg.getBoundingClientRect();
        const scale = box.width / width;
        return [box.left + sx(i) * scale, box.top + (margin.top + 12) * scale];
      };

      overlay.addEventListener("pointermove", (event) => {
        const box = svg.getBoundingClientRect();
        const x = (event.clientX - box.left) * (width / box.width);
        showAt(Math.round(((x - margin.left) / innerW) * (xs.length - 1)), event.clientX, event.clientY);
      });
      overlay.addEventListener("focus", () => showAt(index, ...toClient(index)));
      overlay.addEventListener("keydown", (event) => {
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
        event.preventDefault();
        const next = Math.max(0, Math.min(xs.length - 1, index + (event.key === "ArrowRight" ? 1 : -1)));
        showAt(next, ...toClient(next));
      });
      const hide = () => {
        crosshair.style.visibility = "hidden";
        hideTip();
      };
      overlay.addEventListener("pointerleave", hide);
      overlay.addEventListener("blur", hide);
    };

    draw();
    onWidthChange(container, plot, draw);
  }

  return { bars, lines, showTip, hideTip };
})();
