HeimdallAuth.wireLogoutButton();

function slugify(filename) {
  return filename.trim().replace(/\.[^/.]+$/, "").replace(/\s+/g, "-").toLowerCase();
}

function showMessage(text, kind) {
  const box = document.getElementById("uploadMessage");
  box.textContent = text;
  box.className = "message " + kind;
}

document.getElementById("uploadForm").addEventListener("submit", async (e) => {
  e.preventDefault();

  const fileInput = document.getElementById("fileInput");
  const objectIdInput = document.getElementById("objectId");
  const uploadBtn = document.getElementById("uploadBtn");
  const resultCard = document.getElementById("uploadResult");

  const file = fileInput.files[0];
  if (!file) {
    showMessage("Choose a file first.", "error");
    return;
  }
  const objectId = objectIdInput.value.trim() || slugify(file.name);

  uploadBtn.disabled = true;
  uploadBtn.textContent = "Uploading…";
  resultCard.classList.add("hidden");
  showMessage(`Uploading "${objectId}" (${(file.size / 1_000_000).toFixed(1)} MB)…`, "info");

  try {
    const result = await HeimdallApi.upload(objectId, file);
    showMessage(`Uploaded "${result.objectId}" successfully.`, "success");
    document.getElementById("uploadResultBody").textContent = JSON.stringify(result, null, 2);
    resultCard.classList.remove("hidden");
    fileInput.value = "";
    objectIdInput.value = "";
  } catch (err) {
    showMessage("Upload failed: " + err.message, "error");
  } finally {
    uploadBtn.disabled = false;
    uploadBtn.textContent = "Upload";
  }
});
