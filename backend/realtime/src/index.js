/**
 * 任务记录工具 · 实时推送（Cloudflare Durable Objects）
 *
 * 作用：每个用户对应一个 SyncHub 实例，持有该用户所有设备（手机 App / 悬浮面板 / 网页版）的
 *       WebSocket 长连接。当任一设备写入数据（PUT /api/data 成功）后，Pages Function 通过
 *       「绑定」在 Cloudflare 内部调用本对象，本对象立刻广播给该用户其余设备 —— 秒级同步。
 *
 * 关键点：
 *  - 使用 WebSocket Hibernation API（ctx.acceptWebSocket），连接空闲时不产生 Duration 计费。
 *  - 本 Worker 关闭了 workers.dev 路由；只被 Pages 通过绑定内部调用，不暴露公网。
 *
 * 免费额度（Workers Free 计划，SQLite 后端）：
 *  100,000 请求/天、13,000 GB-s/天；建连计 1 次请求，发出的消息不计费，
 *  收到的消息按 20:1 折算；超额直接拒绝（不会产生账单）。
 */

import { DurableObject } from 'cloudflare:workers';

export class SyncHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
  }

  async fetch(request) {
    const url = new URL(request.url);

    /* 内部调用：广播「数据已更新」 */
    if (url.pathname.endsWith('/notify')) {
      const at = Number(url.searchParams.get('at')) || Date.now();
      const from = url.searchParams.get('from') || '';
      this.broadcast(JSON.stringify({ type: 'changed', at, from }));
      const n = this.ctx.getWebSockets().length;
      return new Response(JSON.stringify({ ok: true, sockets: n }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }

    /* 诊断：当前连接数 */
    if (url.pathname.endsWith('/stat')) {
      return new Response(JSON.stringify({ ok: true, sockets: this.ctx.getWebSockets().length }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }

    /* WebSocket 升级 */
    const upgrade = (request.headers.get('Upgrade') || '').toLowerCase();
    if (upgrade !== 'websocket') {
      return new Response('expected websocket', { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    /* 休眠友好：由运行时接管连接，进程可休眠，连接不断 */
    this.ctx.acceptWebSocket(server);
    try {
      server.serializeAttachment({ since: Date.now() });
    } catch (e) {}

    return new Response(null, { status: 101, webSocket: client });
  }

  broadcast(msg) {
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.send(msg);
      } catch (e) {
        try { ws.close(); } catch (e2) {}
      }
    }
  }

  /* ---- Hibernation API 回调 ---- */

  async webSocketMessage(ws, message) {
    /* 客户端心跳仅用于保活，回一条 pong 即可（不触发数据拉取） */
    if (typeof message === 'string' && message.indexOf('ping') > -1) {
      try { ws.send('{"type":"pong"}'); } catch (e) {}
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try { ws.close(code || 1000, reason || 'closed'); } catch (e) {}
  }

  async webSocketError(ws) {
    try { ws.close(); } catch (e) {}
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/health') {
      return new Response(JSON.stringify({ ok: true, service: 'task-record-realtime' }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }

    /* 兜底：便于直接调试（生产不依赖此路径，Pages 走绑定调用） */
    const key = url.searchParams.get('key') || 'default';
    const id = env.SYNC_HUB.idFromName(key);
    return env.SYNC_HUB.get(id).fetch(request);
  },
};
