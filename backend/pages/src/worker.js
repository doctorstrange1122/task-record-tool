/**
 * 任务记录工具 · 云同步后端（Cloudflare Worker + D1）
 *
 * 接口：
 *   POST /api/register  {username, password}        -> {ok, token}
 *   POST /api/login     {username, password}        -> {ok, token, data, updated_at}
 *   GET  /api/meta      (Bearer token)              -> {ok, updated_at}   仅版本号，供高频探测
 *   GET  /api/data      (Bearer token)              -> {ok, data, updated_at}
 *   PUT  /api/data      (Bearer token) {data, base} -> {ok, updated_at} | 409 冲突
 *   GET  /api/ws        (Bearer / ?token)           -> WebSocket 101      实时推送（转 Durable Object）
 *   POST /api/logout    (Bearer token)              -> {ok}
 *   POST /api/feedback  (Bearer token) {content,image?} -> {ok}               提交反馈（任意登录用户）
 *   GET  /api/feedback  (Bearer token, 管理员)        -> {ok,count,items}     反馈汇总
 *   GET  /api/admin/users (Bearer token, 管理员)      -> {ok,count,users}     注册账号汇总
 *   DELETE /api/admin/users (Bearer token, 管理员){username} -> {ok}          删除单个注册账号
 *   GET  /api/health                                 -> {ok}
 *
 * 安全：PBKDF2-SHA256(120k 次) 加盐哈希；会话令牌 256bit 随机，90 天滑动有效期；
 *       认证接口按 IP 限流（每分钟 20 次）。
 */

const PBKDF2_ITERATIONS = 100000; // Cloudflare 运行时时上限为 100000
const SESSION_DAYS = 90;
const RENEW_WINDOW_MS = 7 * 86400000; // 剩余不足 7 天则续期
const MAX_BODY = 512 * 1024;         // 512KB
const RATE_LIMIT = 20;               // 每分钟
const RATE_WINDOW_MS = 60000;
const ALLOWED_ORIGINS = ['https://doctorstrange1122.github.io'];

function corsHeaders(origin) {
  const ok = origin && (
    ALLOWED_ORIGINS.includes(origin) ||
    /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)
  );
  if (!ok) return {};
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Max-Age': '86400',
    'Vary': 'Origin',
  };
}

function json(body, status, origin, extra) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: Object.assign({
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    }, corsHeaders(origin), extra || {}),
  });
}

function bytesToHex(buf) {
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
}

async function derivePassword(password, saltBytes) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: saltBytes, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    key,
    256
  );
  return new Uint8Array(bits);
}

function newToken() {
  return bytesToHex(crypto.getRandomValues(new Uint8Array(32)));
}

/** 简单的常量时间比较，防时序侧信道 */
function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function clientIp(request) {
  return request.headers.get('CF-Connecting-IP') || 'unknown';
}

/** 管理员判定：仅账号 1473949160 具备管理权限 */
function isAdmin(auth) {
  return !!(auth && auth.username === '1473949160');
}

/** 按 IP 对认证接口限流 */
async function rateLimit(db, request, scope) {
  const now = Date.now();
  const window = Math.floor(now / RATE_WINDOW_MS);
  const key = scope + ':' + clientIp(request);
  try {
    await db.prepare(
      `INSERT INTO rate_limits (key, window, count) VALUES (?, ?, 1)
       ON CONFLICT(key, window) DO UPDATE SET count = count + 1`
    ).bind(key, window).run();
    const row = await db.prepare(
      'SELECT count FROM rate_limits WHERE key = ? AND window = ?'
    ).bind(key, window).first();
    return (row && row.count ? row.count : 1) <= RATE_LIMIT;
  } catch (e) {
    return true; // 限流表异常时不阻断正常使用
  }
}

/** 校验 Bearer 令牌（或显式传入的令牌，供 WebSocket 用 query 传参）；返回 {userId,username} 或 null */
async function authenticate(db, request, extraToken) {
  let token = null;
  if (typeof extraToken === 'string' && /^[a-f0-9]{64}$/i.test(extraToken)) {
    token = extraToken.toLowerCase();
  } else {
    const header = request.headers.get('Authorization') || '';
    const m = header.match(/^Bearer\s+([a-f0-9]{64})$/i);
    if (!m) return null;
    token = m[1].toLowerCase();
  }
  const now = Date.now();
  const row = await db.prepare(
    `SELECT s.token, s.expires_at, u.id AS user_id, u.username
     FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token = ? AND s.expires_at > ?`
  ).bind(token, now).first();
  if (!row) return null;
  if (row.expires_at < now + RENEW_WINDOW_MS) {
    await db.prepare('UPDATE sessions SET expires_at = ? WHERE token = ?')
      .bind(now + SESSION_DAYS * 86400000, token).run();
  }
  return { userId: row.user_id, username: row.username };
}

async function readBody(request) {
  const type = (request.headers.get('Content-Type') || '').toLowerCase();
  if (!type.includes('application/json')) return { error: 'Content-Type 须为 application/json' };
  const len = Number(request.headers.get('Content-Length') || 0);
  if (len > MAX_BODY) return { error: '请求体过大' };
  try {
    return { value: await request.json() };
  } catch (e) {
    return { error: 'JSON 解析失败' };
  }
}

function validUsername(name) {
  return typeof name === 'string' && /^\d{7,11}$/.test(name);
}

function validNickname(nick) {
  return typeof nick === 'string' && nick.length >= 1 && nick.length <= 20 && !/[<>]/.test(nick);
}

function validPassword(pw) {
  return typeof pw === 'string' && pw.length >= 6 && pw.length <= 100;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const origin = request.headers.get('Origin');
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }

    if (path === '/api/health') {
      return json({ ok: true, service: 'task-record-api' }, 200, origin);
    }

    if (!path.startsWith('/api/')) {
      return json({ ok: false, error: 'Not Found' }, 404, origin);
    }

    const db = env.DB;
    if (!db) return json({ ok: false, error: '服务端存储未配置' }, 500, origin);

    try {
      /* ---------- 注册 ---------- */
      if (path === '/api/register' && request.method === 'POST') {
        if (!(await rateLimit(db, request, 'register'))) {
          return json({ ok: false, error: '操作过于频繁，请稍后再试' }, 429, origin);
        }
        const body = await readBody(request);
        if (body.error) return json({ ok: false, error: body.error }, 400, origin);
        const { username, password, nickname } = body.value || {};
        if (!validUsername(username)) {
          return json({ ok: false, error: '账号须为 7-11 位数字' }, 400, origin);
        }
        if (!validPassword(password)) {
          return json({ ok: false, error: '密码长度须为 6-100 个字符' }, 400, origin);
        }
        if (!validNickname(nickname)) {
          return json({ ok: false, error: '昵称须为 1-20 个字符' }, 400, origin);
        }
        const exists = await db.prepare('SELECT id FROM users WHERE username = ?').bind(username).first();
        if (exists) return json({ ok: false, error: '该账号已被注册，请更换' }, 409, origin);

        const salt = crypto.getRandomValues(new Uint8Array(16));
        const hash = await derivePassword(password, salt);
        const res = await db.prepare(
          'INSERT INTO users (username, salt, pass_hash, nickname, created_at) VALUES (?, ?, ?, ?, ?)'
        ).bind(username, bytesToHex(salt), bytesToHex(hash), nickname, Date.now()).run();
        const userId = res.meta.last_row_id;
        const token = newToken();
        await db.prepare(
          'INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)'
        ).bind(token, userId, Date.now(), Date.now() + SESSION_DAYS * 86400000).run();
        return json({ ok: true, token, username, nickname }, 200, origin);
      }

      /* ---------- 登录 ---------- */
      if (path === '/api/login' && request.method === 'POST') {
        if (!(await rateLimit(db, request, 'login'))) {
          return json({ ok: false, error: '尝试过于频繁，请稍后再试' }, 429, origin);
        }
        const body = await readBody(request);
        if (body.error) return json({ ok: false, error: body.error }, 400, origin);
        const { username, password } = body.value || {};
        if (typeof username !== 'string' || typeof password !== 'string') {
          return json({ ok: false, error: '参数不完整' }, 400, origin);
        }
        const user = await db.prepare(
          'SELECT id, salt, pass_hash, nickname FROM users WHERE username = ?'
        ).bind(username).first();
        /* 即使用户不存在也执行一次哈希，避免时间差探测 */
        const salt = user ? hexToBytes(user.salt) : new Uint8Array(16);
        const hash = bytesToHex(await derivePassword(password, salt));
        if (!user || !safeEqual(hash, user.pass_hash)) {
          return json({ ok: false, error: '账号或密码错误' }, 401, origin);
        }
        const dataRow = await db.prepare(
          'SELECT data, updated_at FROM user_data WHERE user_id = ?'
        ).bind(user.id).first();
        const token = newToken();
        await db.prepare(
          'INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)'
        ).bind(token, user.id, Date.now(), Date.now() + SESSION_DAYS * 86400000).run();
        return json({
          ok: true,
          token,
          username,
          nickname: user.nickname || username,
          data: dataRow ? JSON.parse(dataRow.data) : null,
          updated_at: dataRow ? dataRow.updated_at : null,
        }, 200, origin);
      }

      /* ---------- 轻量探测：只返回版本号（updated_at），供前端高频轮询 ---------- */
      /* 客户端轮询它，仅当 updated_at 大于本地 lastSync 时才拉取全量数据，省流量、低延迟 */
      if (path === '/api/meta' && request.method === 'GET') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效，请重新登录' }, 401, origin);
        const row = await db.prepare(
          'SELECT updated_at FROM user_data WHERE user_id = ?'
        ).bind(auth.userId).first();
        return json({ ok: true, updated_at: row ? row.updated_at : 0 }, 200, origin);
      }

      /* ---------- 实时推送：WebSocket 升级 ---------- */
      /* 客户端连 wss://task-record-api.pages.dev/api/ws?token=xxx；
         升级请求通过「绑定」转给 Durable Object（Cloudflare 内部调用，不经过 workers.dev） */
      if (path === '/api/ws' && request.method === 'GET') {
        const upgrade = (request.headers.get('Upgrade') || '').toLowerCase();
        if (upgrade !== 'websocket') {
          return json({ ok: false, error: '需要 WebSocket 升级请求' }, 426, origin);
        }
        const auth = await authenticate(db, request, url.searchParams.get('token'));
        if (!auth) return json({ ok: false, error: '登录已失效，请重新登录' }, 401, origin);
        if (!env.SYNC_HUB) return json({ ok: false, error: '实时服务未配置' }, 503, origin);
        const id = env.SYNC_HUB.idFromName('u' + auth.userId);
        return env.SYNC_HUB.get(id).fetch(request);
      }

      /* ---------- 读取数据 ---------- */
      if (path === '/api/data' && request.method === 'GET') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效，请重新登录' }, 401, origin);
        const row = await db.prepare(
          'SELECT data, updated_at FROM user_data WHERE user_id = ?'
        ).bind(auth.userId).first();
        return json({
          ok: true,
          data: row ? JSON.parse(row.data) : null,
          updated_at: row ? row.updated_at : null,
        }, 200, origin);
      }

      /* ---------- 保存数据（带版本冲突保护） ---------- */
      if (path === '/api/data' && request.method === 'PUT') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效，请重新登录' }, 401, origin);
        const body = await readBody(request);
        if (body.error) return json({ ok: false, error: body.error }, 400, origin);
        const { data: payload, base } = body.value || {};
        if (!payload || typeof payload !== 'object') {
          return json({ ok: false, error: '数据格式不正确' }, 400, origin);
        }
        const serialized = JSON.stringify(payload);
        if (serialized.length > MAX_BODY) {
          return json({ ok: false, error: '数据过大（上限 512KB）' }, 413, origin);
        }
        const row = await db.prepare(
          'SELECT updated_at FROM user_data WHERE user_id = ?'
        ).bind(auth.userId).first();
        const serverAt = row ? row.updated_at : 0;
        const baseAt = typeof base === 'number' ? base : 0;
        /* 服务器上有比自己更新的写入（其他设备），拒绝覆盖 */
        if (serverAt > baseAt) {
          return json({ ok: false, error: '其他设备有更新', server_updated_at: serverAt }, 409, origin);
        }
        const now = Date.now();
        await db.prepare(
          `INSERT INTO user_data (user_id, data, updated_at) VALUES (?, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at`
        ).bind(auth.userId, serialized, now).run();
        /* 写入成功 → 通知该用户的其他设备（通过 DO 绑定内部调用）→ 秒级同步 */
        await notifyChange(env, auth.userId, now);
        return json({ ok: true, updated_at: now }, 200, origin);
      }

      /* ---------- 退出登录 ---------- */
      if (path === '/api/logout' && request.method === 'POST') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效' }, 401, origin);
        const header = request.headers.get('Authorization') || '';
        const token = header.replace(/^Bearer\s+/i, '').toLowerCase();
        await db.prepare('DELETE FROM sessions WHERE token = ?').bind(token).run();
        return json({ ok: true }, 200, origin);
      }

      /* ---------- 提交反馈（任意登录用户） ---------- */
      if (path === '/api/feedback' && request.method === 'POST') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '请先登录后再提交反馈' }, 401, origin);
        const body = await readBody(request);
        if (body.error) return json({ ok: false, error: body.error }, 400, origin);
        const { content, image } = body.value || {};
        if (typeof content !== 'string' || !content.trim()) {
          return json({ ok: false, error: '反馈内容不能为空' }, 400, origin);
        }
        if (content.trim().length > 2000) {
          return json({ ok: false, error: '反馈内容过长（上限 2000 字）' }, 400, origin);
        }
        let img = null;
        if (image != null) {
          if (typeof image !== 'string' ||
              !/^data:image\/[a-zA-Z0-9.+-]+;base64,/.test(image)) {
            return json({ ok: false, error: '图片格式不正确' }, 400, origin);
          }
          if (image.length > 1400000) { /* 约 1MB 的 base64 */
            return json({ ok: false, error: '图片过大（建议 ≤ 1MB）' }, 413, origin);
          }
          img = image;
        }
        await db.prepare(
          'INSERT INTO feedback (user_id, username, content, image, status, created_at) VALUES (?, ?, ?, ?, 0, ?)'
        ).bind(auth.userId, auth.username, content.trim(), img, Date.now()).run();
        return json({ ok: true }, 200, origin);
      }

      /* ---------- 查看反馈汇总（管理员） ---------- */
      if (path === '/api/feedback' && request.method === 'GET') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效' }, 401, origin);
        if (!isAdmin(auth)) return json({ ok: false, error: '无权限' }, 403, origin);
        const rows = await db.prepare(
          'SELECT id, username, content, image, status, created_at FROM feedback ORDER BY created_at DESC LIMIT 200'
        ).all();
        const items = (rows.results || []).map(function (r) {
          return {
            id: r.id,
            username: r.username,
            content: r.content,
            image: r.image || null,
            status: r.status,
            created_at: r.created_at,
          };
        });
        return json({ ok: true, count: items.length, items: items }, 200, origin);
      }

      /* ---------- 注册账号管理：汇总列表（管理员） ---------- */
      if (path === '/api/admin/users' && request.method === 'GET') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效' }, 401, origin);
        if (!isAdmin(auth)) return json({ ok: false, error: '无权限' }, 403, origin);
        const total = await db.prepare('SELECT COUNT(*) AS c FROM users').first();
        const rows = await db.prepare(
          'SELECT username, nickname, created_at FROM users ORDER BY created_at DESC'
        ).all();
        return json({
          ok: true,
          count: total ? total.c : 0,
          users: rows.results || [],
        }, 200, origin);
      }

      /* ---------- 注册账号管理：删除单个账号（管理员） ---------- */
      if (path === '/api/admin/users' && request.method === 'DELETE') {
        const auth = await authenticate(db, request);
        if (!auth) return json({ ok: false, error: '登录已失效' }, 401, origin);
        if (!isAdmin(auth)) return json({ ok: false, error: '无权限' }, 403, origin);
        const body = await readBody(request);
        if (body.error) return json({ ok: false, error: body.error }, 400, origin);
        const { username } = body.value || {};
        if (!validUsername(username)) {
          return json({ ok: false, error: '账号格式不正确' }, 400, origin);
        }
        if (username === auth.username) {
          return json({ ok: false, error: '不能删除管理员自身' }, 400, origin);
        }
        const u = await db.prepare('SELECT id FROM users WHERE username = ?').bind(username).first();
        if (u) {
          const uid = u.id;
          await db.batch([
            db.prepare('DELETE FROM sessions WHERE user_id = ?').bind(uid),
            db.prepare('DELETE FROM user_data WHERE user_id = ?').bind(uid),
            db.prepare('DELETE FROM feedback WHERE user_id = ?').bind(uid),
            db.prepare('DELETE FROM users WHERE id = ?').bind(uid),
          ]);
        }
        return json({ ok: true }, 200, origin);
      }

      return json({ ok: false, error: 'Not Found' }, 404, origin);
    } catch (e) {
      return json({ ok: false, error: '服务器内部错误' }, 500, origin);
    }
  },
};

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

/**
 * 通知该用户的 Durable Object 广播「数据已更新」。
 * 走 Cloudflare 内部绑定，不经过公网；失败不影响主流程（前端还有 /api/meta 轮询兜底）。
 */
async function notifyChange(env, userId, at) {
  try {
    if (!env.SYNC_HUB) return;
    const id = env.SYNC_HUB.idFromName('u' + userId);
    await env.SYNC_HUB.get(id).fetch(
      'https://do/notify?at=' + encodeURIComponent(String(at)),
      { method: 'POST' }
    );
  } catch (e) {
    /* 实时推送不可用时静默降级为轮询 */
  }
}
