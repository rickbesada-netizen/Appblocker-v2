// Minimal service worker so the app is installable (required by PWABuilder).
const CACHE = 'appblocker-remote-v1';
const ASSETS = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).catch(()=>{}));
  self.skipWaiting();
});
self.addEventListener('activate', e => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // Only serve our own files from cache; never cache Supabase API calls.
  if (url.origin === location.origin) {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
  }
});
