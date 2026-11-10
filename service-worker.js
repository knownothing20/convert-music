/**
 * 自毁型 Service Worker（由原版 Workbox precache 替换而来）
 *
 * 原版用 workbox.precaching 把整个页面缓存下来，导致浏览器始终返回旧界面、
 * 无法更新到新版本。这里改为：安装即接管，激活后清空所有缓存并注销自身。
 * 浏览器下次检查更新时会走到这里，从而自动把旧缓存清掉。
 */

self.addEventListener("install", function () {
  // 立即激活，不等旧页面关闭
  self.skipWaiting();
});

self.addEventListener("activate", function (event) {
  event.waitUntil(
    (async function () {
      try {
        var keys = await caches.keys();
        await Promise.all(keys.map(function (k) { return caches.delete(k); }));
      } catch (e) { /* 忽略 */ }

      try { await self.clients.claim(); } catch (e) { /* 忽略 */ }

      // 注销自己，之后页面完全走网络
      try { await self.registration.unregister(); } catch (e) { /* 忽略 */ }
    })()
  );
});

self.addEventListener("fetch", function () {
  // 不做任何拦截，全部走网络
});
