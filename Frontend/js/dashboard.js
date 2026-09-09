HeimdallAuth.wireLogoutButton();

const CHART_COLORS = ["#22d3ee", "#a78bfa", "#fb923c", "#34d399", "#f472b6", "#facc15"];

let uploadsChart = null;
let readsChart = null;

function makeBarChart(canvasId) {
  const ctx = document.getElementById(canvasId).getContext("2d");
  return new Chart(ctx, {
    type: "bar",
    data: { labels: [], datasets: [{ data: [], backgroundColor: [] }] },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { color: "#96a2ae" }, grid: { color: "#262d37" } },
        y: { beginAtZero: true, ticks: { color: "#96a2ae", precision: 0 }, grid: { color: "#262d37" } },
      },
    },
  });
}

function updateBarChart(chart, byPrimary) {
  const labels = Object.keys(byPrimary).sort();
  chart.data.labels = labels;
  chart.data.datasets[0].data = labels.map((label) => byPrimary[label]);
  chart.data.datasets[0].backgroundColor = labels.map((_, i) => CHART_COLORS[i % CHART_COLORS.length]);
  chart.update();
}

function renderStats(summary) {
  document.getElementById("statUploads").textContent = summary.totalUploads;
  document.getElementById("statDownloads").textContent = summary.totalDownloads;
  document.getElementById("statFailovers").textContent = summary.failoverCount;
  document.getElementById("statLatency").textContent = summary.avgChunkFetchLatencyMs.toFixed(1) + " ms";
}

function renderHealth(nodes) {
  const container = document.getElementById("nodeList");
  if (!nodes.length) {
    container.innerHTML = '<div class="empty-state">No nodes configured.</div>';
    return;
  }
  container.innerHTML = nodes
    .map(
      (node) => `
      <div class="node-row">
        <span class="status-dot ${node.status === "UP" ? "up" : "down"}"></span>
        <span>${node.id}</span>
        <span class="role-badge">${node.role}</span>
        <span class="node-url">${node.baseUrl}</span>
      </div>`
    )
    .join("");
}

function showError(message) {
  const box = document.getElementById("dashboardError");
  box.textContent = message;
  box.classList.remove("hidden");
}

function clearError() {
  document.getElementById("dashboardError").classList.add("hidden");
}

async function refresh() {
  try {
    const [summary, health] = await Promise.all([HeimdallApi.metricsSummary(), HeimdallApi.clusterHealth()]);
    clearError();
    renderStats(summary);
    updateBarChart(uploadsChart, summary.uploadsByPrimary);
    updateBarChart(readsChart, summary.readsByPrimary);
    renderHealth(health);
  } catch (err) {
    showError("Couldn't reach the gateway: " + err.message);
  }
}

uploadsChart = makeBarChart("uploadsChart");
readsChart = makeBarChart("readsChart");
refresh();
setInterval(refresh, 3000);
