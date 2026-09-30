// Offline support for the Italy Travel Pocket Guide.
// Cache-first: the app opens instantly with no signal. Whenever the network is available the
// cached copy is refreshed in the background, so updates show up on the next launch.
// Bump CACHE when APP_SHELL changes; old itguide-* caches are removed on activate.
const CACHE = 'itguide-private-1.19-colosseum-20260930';
const APP_SHELL = [
  './',
  './manifest.webmanifest',
  './conversation.js',
  './conversation-view.js',
  './conversation.css',
  './sentence-compiler.js',
  './today.js',
  './today.css',
  './speech-readiness.js',
  './speech-readiness.css',
  './trip.js',
  './trip.css',
  './trip-content.json',
  './trip-content-anchors.json',
  './user-data.js',
  './personal.js',
  './json-transfer.js',
  './wallet-web-port.js',
  './wallet-channel.js',
  './wallet-host.js',
  './wallet.js',
  './wallet-ui.js',
  './attachments.js',
  './attachments.css',
  './places.js',
  './places.css',
  './saved.js',
  './saved.css',
  './learning.js',
  './learning.css',
  './learning-content.json',
  './third-party-licenses.txt',
  './icons/apple-touch-icon.png',
  './icons/google-translate-attribution.png',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './destination-guide.js',
  './destination-guide.css',
  './destination-content.json',
  './photo-credits.json',
  './colosseum-guide.js',
  './colosseum-guide.css',
  './colosseum-content.json',
  './photos/itinerary/colosseum-context.webp',
  './photos/itinerary/colosseum-vault.webp',
  './photos/itinerary/colosseum-machinery.webp',
  './photos/itinerary/colosseum-backstage.webp',
  './photos/itinerary/colosseum-gallery.webp',
  './photos/itinerary/colosseum-middle.webp',
  './photos/itinerary/colosseum-bowl.webp',
  './photos/itinerary/colosseum-floor.webp',
  './photos/itinerary/colosseum-seats.webp',
  './photos/itinerary/colosseum-arches.webp',
  './photos/itinerary/colosseum-corridor.webp',
  './photos/itinerary/colosseum-arena.webp',
  './photos/itinerary/colosseum-underground.webp',
  './photos/itinerary/colosseum-panorama.webp',
  './photos/itinerary/pantheon.webp',
  './photos/itinerary/piazza-navona.webp',
  './photos/itinerary/colosseum.webp',
  './photos/itinerary/roman-forum.webp',
  './photos/itinerary/vatican.webp',
  './photos/itinerary/trevi-fountain.webp',
  './photos/itinerary/spanish-steps.webp',
  './photos/itinerary/carbonara.webp',
  './photos/itinerary/suppli.webp',
  './photos/itinerary/maritozzo.webp',
  './photos/itinerary/espresso.webp',
  './photos/itinerary/white-wine.webp',
  './photos/itinerary/castel-nuovo.webp',
  './photos/itinerary/piazza-municipio-naples.webp',
  './photos/itinerary/pizza.webp',
  './photos/itinerary/sfogliatella.webp',
  './photos/itinerary/villa-comunale-sorrento.webp',
  './photos/itinerary/chiostro-san-francesco-sorrento.webp',
  './photos/itinerary/piazza-tasso.webp',
  './photos/itinerary/gnocchi-sorrentina.webp',
  './photos/itinerary/delizia-limone.webp',
  './photos/itinerary/limoncello.webp',
  './photos/itinerary/pompeii-forum.webp',
  './photos/itinerary/pompeii-theatre.webp',
  './photos/itinerary/pompeii-baths.webp',
  './photos/itinerary/caprese-salad.webp',
  './photos/itinerary/pastiera.webp',
  './photos/itinerary/positano-beach.webp',
  './photos/itinerary/santa-maria-assunta-positano.webp',
  './photos/itinerary/seafood-salad.webp',
  './photos/itinerary/amalfi-cathedral.webp',
  './photos/itinerary/scialatielli.webp',
  './photos/itinerary/lemon-granita.webp',
  './photos/itinerary/villa-rufolo.webp',
  './photos/itinerary/ravello-duomo.webp',
  './photos/itinerary/torta-caprese.webp',
  './photos/itinerary/amalfi-waterfront.webp',
  './photos/itinerary/lemonade.webp'
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
