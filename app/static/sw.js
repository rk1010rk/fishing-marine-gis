const CACHE_NAME = 'fishing-map-v1';
const urlsToCache = [
  '/',
  '/static/css/style.css',
  '/static/js/map.js',
  'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css',
  'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(urlsToCache))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cacheName) => {
          if (cacheName !== CACHE_NAME) {
            return caches.delete(cacheName);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.url.includes('/api/')) {
    return;
  }
  
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const cloneResponse = response.clone();
        caches.open(CACHE_NAME).then((cache) => {
          cache.put(event.request, cloneResponse);
        });
        return response;
      })
      .catch(() => {
        return caches.match(event.request)
          .then((response) => response || new Response('Offline'));
      })
  );
});
