const CACHE_NAME = "send-honey-where-v8";
const CORE_ASSETS = [
  "/",
  "/send",
  "/receive",
  "/p2p/send",
  "/p2p/receive",
  "/manifest.webmanifest",
  "/static/css/style.css?v=send-honey-where",
  "/static/js/common.js?v=send-honey-where-7",
  "/static/js/stored_send.js?v=send-honey-where-2",
  "/static/js/stored_receive.js?v=send-honey-where-1",
  "/static/js/webrtc_protocol.js?v=send-honey-where-6",
  "/static/js/send.js?v=send-honey-where-7",
  "/static/js/receive.js?v=send-honey-where-6",
  "/static/img/honey-transfer-hero.png",
  "/static/img/pwa-icon-192.png",
  "/static/img/pwa-icon-512.png"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(CORE_ASSETS))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);

  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          return response;
        })
        .catch(() => caches.match(request).then((cached) => cached || caches.match("/")))
    );
    return;
  }

  event.respondWith(
    caches.match(request)
      .then((cached) => cached || fetch(request).then((response) => {
        const copy = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        return response;
      }))
  );
});
