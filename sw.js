/* Service Worker – macht die App offline nutzbar.
   Bei jeder neuen App-Version CACHE hochzählen (und APP_VERSION in app.js). */
const CACHE = 'rechnungen-1.0.1';
const FILES = ['./', './index.html', './boot.js', './app.css', './app.js', './inter.woff2', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png', './icon-180.png',
  './lib/pdfjs/pdf.min.js', './lib/pdfjs/pdf.worker.min.js', './lib/pdfjs/wasm/jbig2.wasm', './lib/pdfjs/wasm/openjpeg.wasm'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES.map(f => new Request(f, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  // Nur eigene Dateien – Anfragen an die Claude-API laufen nie über den Cache.
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then(hit => hit || fetch(req).then(res => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); }
      return res;
    }).catch(() => req.mode === 'navigate' ? caches.match('./index.html') : Response.error()))
  );
});
