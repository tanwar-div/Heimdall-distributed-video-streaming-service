// Small fetch wrapper around the gateway's REST API. Every method throws an
// Error with a readable .message on failure (parsed from the gateway's own
// ErrorResponseDto JSON body where possible) so callers can just try/catch.
const HeimdallApi = (() => {
  const base = HEIMDALL_CONFIG.GATEWAY_BASE_URL;

  async function parseErrorBody(response) {
    try {
      const body = await response.json();
      if (body && body.message) return body.message;
    } catch (_) {
      // body wasn't JSON (e.g. a truncated streaming response) - fall through
    }
    return `${response.status} ${response.statusText}`;
  }

  async function getJson(path) {
    const response = await fetch(base + path);
    if (!response.ok) throw new Error(await parseErrorBody(response));
    return response.json();
  }

  return {
    baseUrl: base,

    listObjects() {
      return getJson("/objects");
    },

    clusterTopology() {
      return getJson("/cluster");
    },

    clusterHealth() {
      return getJson("/cluster/health");
    },

    metricsSummary() {
      return getJson("/metrics/summary");
    },

    replicaInfo(objectId) {
      return getJson(`/objects/${encodeURIComponent(objectId)}/replicas`);
    },

    videoUrl(objectId) {
      return `${base}/objects/${encodeURIComponent(objectId)}`;
    },

    async upload(objectId, file) {
      const response = await fetch(`${base}/objects/${encodeURIComponent(objectId)}`, {
        method: "POST",
        headers: {
          "X-API-Key": HEIMDALL_CONFIG.API_KEY,
          "Content-Type": file.type || "application/octet-stream",
        },
        body: file,
      });
      if (!response.ok) throw new Error(await parseErrorBody(response));
      return response.json();
    },

    async deleteObject(objectId) {
      const response = await fetch(`${base}/objects/${encodeURIComponent(objectId)}`, {
        method: "DELETE",
        headers: { "X-API-Key": HEIMDALL_CONFIG.API_KEY },
      });
      if (!response.ok) throw new Error(await parseErrorBody(response));
    },
  };
})();
