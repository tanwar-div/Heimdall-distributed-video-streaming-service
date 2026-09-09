HeimdallAuth.wireLogoutButton();

function showError(message) {
  const box = document.getElementById("libraryError");
  box.textContent = message;
  box.classList.remove("hidden");
}

function clearError() {
  document.getElementById("libraryError").classList.add("hidden");
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value;
  return div.innerHTML;
}

function renderRows(objectIds) {
  const tbody = document.getElementById("objectRows");
  const emptyState = document.getElementById("emptyState");

  if (!objectIds.length) {
    tbody.innerHTML = "";
    emptyState.classList.remove("hidden");
    return;
  }
  emptyState.classList.add("hidden");

  tbody.innerHTML = objectIds
    .map(
      (id) => `
      <tr>
        <td>${escapeHtml(id)}</td>
        <td class="actions">
          <button class="play" data-id="${escapeHtml(id)}">Play</button>
          <button class="info" data-id="${escapeHtml(id)}">Info</button>
          <button class="danger delete" data-id="${escapeHtml(id)}">Delete</button>
        </td>
      </tr>`
    )
    .join("");

  tbody.querySelectorAll(".play").forEach((btn) => btn.addEventListener("click", () => playObject(btn.dataset.id)));
  tbody.querySelectorAll(".info").forEach((btn) => btn.addEventListener("click", () => showInfo(btn.dataset.id)));
  tbody.querySelectorAll(".delete").forEach((btn) => btn.addEventListener("click", () => deleteObject(btn.dataset.id)));
}

async function loadLibrary() {
  try {
    const objectIds = await HeimdallApi.listObjects();
    clearError();
    renderRows(objectIds);
  } catch (err) {
    showError("Couldn't load the library: " + err.message);
  }
}

function playObject(objectId) {
  document.getElementById("playerTitle").textContent = "Now playing: " + objectId;
  document.getElementById("player").src = HeimdallApi.videoUrl(objectId);
  document.getElementById("playerCard").classList.remove("hidden");
  document.getElementById("playerCard").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function showInfo(objectId) {
  try {
    const info = await HeimdallApi.replicaInfo(objectId);
    document.getElementById("infoTitle").textContent = "Routing decision: " + objectId;
    document.getElementById("infoBody").textContent = JSON.stringify(info, null, 2);
    document.getElementById("infoCard").classList.remove("hidden");
    document.getElementById("infoCard").scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (err) {
    showError("Couldn't load routing info: " + err.message);
  }
}

async function deleteObject(objectId) {
  if (!confirm(`Delete "${objectId}" from its primary and all replicas?`)) return;
  try {
    await HeimdallApi.deleteObject(objectId);
    await loadLibrary();
  } catch (err) {
    showError("Delete failed: " + err.message);
  }
}

document.getElementById("refreshBtn").addEventListener("click", loadLibrary);
loadLibrary();
