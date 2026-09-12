// Settings for the showcase page.
//
// demoVideo - the demo recording. Any of these work:
//   - a YouTube link (watch, youtu.be, shorts or embed form)
//   - a Vimeo link
//   - a file inside docs/, e.g. "media/demo.mp4". Keep it under about 50 MB:
//     GitHub warns above that and rejects files over 100 MB.
// demoPoster - optional image shown before a local video file starts.
//
// Recording the demo: start the cluster (docker compose up --build), upload a
// video as "showcase.mp4":
//
//   curl -X POST http://localhost:8080/objects/showcase.mp4 \
//     -H "X-API-Key: changeme" -H "Content-Type: video/mp4" --data-binary @video.mp4
//
// then open this page with ?backend=http://localhost:8080 on the end of the URL.
// The Demo section becomes a live view of the cluster: the video streamed
// through Heimdall, live counters, and which node served each chunk.
window.HEIMDALL_SITE = {
  demoVideo: "media/product-demo-gif.gif",
  demoPoster: "",
  demoAlt: "Heimdall streaming a video while chunk requests spread across the shard's replicas.",
  videoObjectId: "showcase.mp4",
  repoUrl: "https://github.com/tanwar-div/Heimdall-distributed-video-streaming-service",
  branch: "main",
};
