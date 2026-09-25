// Offline support for the Italy Travel Pocket Guide.
// Cache-first: the app opens instantly with no signal. Whenever the network is available the
// cached copy is refreshed in the background, so updates show up on the next launch.
// Bump CACHE when APP_SHELL changes; old itguide-* caches are removed on activate.
const CACHE = 'itguide-vocab-v1';
const APP_SHELL = [
  './',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/apple-touch-icon.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      // Other GitHub Pages sites share this origin's cache storage: only touch our own caches
      .then(keys => Promise.all(keys
        .filter(key => key.startsWith('itguide-') && key !== CACHE)
        .map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;

  const network = fetch(request);

  // Refresh the cache in the background. Clone before the page starts reading the body.
  event.waitUntil(network
    .then(response => {
      if (!response.ok || response.redirected || response.type !== 'basic') return;
      const copy = response.clone();
      return caches.open(CACHE).then(cache => cache.put(request, copy));
    })
    .catch(() => { /* offline: the cached copy is served below */ }));

  event.respondWith((async () => {
    const cached = await caches.match(request, { ignoreSearch: request.mode === 'navigate' });
    if (cached) return cached;
    try {
      return await network;
    } catch (err) {
      // Offline and not cached (e.g. /index.html): fall back to the app itself
      if (request.mode === 'navigate') {
        const app = await caches.match('./');
        if (app) return app;
      }
      throw err;
    }
  })());
});
