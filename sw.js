/**
 * 任务记录工具 · Service Worker
 *
 * 作用：接收服务端（Cloudflare Cron Worker）发来的 Web Push 消息，
 *       调用系统通道弹出通知。页面关闭 / 后台 / 锁屏时依然有效。
 *
 * 注意：本文件必须与页面同源（部署在 GitHub Pages 同一目录），
 *       且站点需通过 HTTPS 访问。
 */

self.addEventListener('install', function () {
  self.skipWaiting();
});

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim());
});

/* ---------- 收到推送 ---------- */
self.addEventListener('push', function (event) {
  var data = {};
  if (event.data) {
    try {
      data = event.data.json();
    } catch (e) {
      try {
        data = { body: event.data.text() };
      } catch (e2) {
        data = {};
      }
    }
  }

  var title = data.title || '该完成任务了';
  var body = data.body || (data.task ? data.task : '');
  if (data.rule) body = body + ' · ' + data.rule;

  var options = {
    body: body,
    icon: './icon-192.png',
    badge: './icon-192.png',
    tag: data.tag || 'task-reminder',
    renotify: true,
    requireInteraction: false,
    vibrate: [200, 120, 200],
    data: { url: data.url || './index.html' }
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

/* ---------- 点击通知：打开/聚焦页面 ---------- */
self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var url = (event.notification.data && event.notification.data.url) || './index.html';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
      for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (c.url && c.url.indexOf('index.html') !== -1 && 'focus' in c) {
          return c.focus();
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(url);
      return undefined;
    })
  );
});
