// WebHTV cloud backend for EdgeOne Pages Edge Functions.
// Routes:
//   POST /api/auth/register | /api/auth/login | /api/auth/logout | /api/auth/password
//   GET  /api/auth/me
//   POST /api/playback/events   (used as the playback webhook)
//   GET  /api/playback/sync     (pull changes since cursor)
//   POST /api/backup/save       (snapshot backup: keeps / update tracks)
//   GET  /api/backup/load?type=keep
//   GET  /api/public/list       (shared folder, login required)
//   GET  /api/public/get?key=   (shared folder, login required)
//   POST /api/public/put        (shared folder, admin code required)
//   POST /api/public/delete     (shared folder, admin code required)
//
// Storage: EdgeOne Pages Blob (@edgeone/pages-blob). The store is created on
// first write, so the project needs no manual KV namespace approval.
//
// Cross-app sharing: user identity (u_<uid>) and all user-owned keys are keyed
// by uid only, so a second client app using this backend can read the same
// account data by presenting the same token. The optional x-webhtv-app header
// (default "webhtv") is recorded as metadata only and never partitions data.

import {getStore} from '@edgeone/pages-blob';

const STORE_NAME = 'webhtv';
const APP_ID = 'webhtv';
// Keep in sync with BuildConfig.ADMIN_CODE. Change this after deployment.
const ADMIN_CODE = 'webhtv';
const PUBLIC_PREFIX = 'pub_';

const JSON_HEADERS = {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET,POST,OPTIONS',
    'access-control-allow-headers': 'content-type,authorization,x-webhtv-token,x-webhtv-config-key,x-webhtv-since,x-webhtv-limit,idempotency-key,x-webhtv-webhook-id,x-webhtv-dedupe-key,x-webhtv-admin,x-webhtv-app',
};

const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const PBKDF2_ITERATIONS = 60000;
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_EVENTS_PER_REQUEST = 20;

function json(status, body) {
    return new Response(JSON.stringify(body), {status, headers: JSON_HEADERS});
}

function ok(body) {
    return json(200, Object.assign({ok: true}, body || {}));
}

function fail(status, error) {
    return json(status, {ok: false, error: error});
}

let sharedStore = null;

function blobStore() {
    if (!sharedStore) sharedStore = getStore({name: STORE_NAME, consistency: 'strong'});
    return sharedStore;
}

// KV-compatible adapter over Pages Blob: get / put / delete / list({prefix}).
function kvOf(context) {
    return {
        get: async function (key) {
            const value = await blobStore().get(key, {type: 'text', consistency: 'strong'});
            return value == null ? null : value;
        },
        put: async function (key, value) {
            await blobStore().set(key, String(value));
        },
        delete: async function (key) {
            await blobStore().delete(key);
        },
        list: async function (options) {
            const result = await blobStore().list({prefix: options.prefix, consistency: 'strong'});
            const blobs = (result && result.blobs) || [];
            return {keys: blobs.map(function (blob) {
                return {key: blob.key};
            }), complete: true};
        },
    };
}

function toHex(buffer) {
    return Array.from(new Uint8Array(buffer)).map(function (b) {
        return b.toString(16).padStart(2, '0');
    }).join('');
}

function fromHex(hex) {
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
}

function randomHex(bytes) {
    const buffer = new Uint8Array(bytes);
    crypto.getRandomValues(buffer);
    return toHex(buffer);
}

async function sha256hex(text) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
    return toHex(digest);
}

async function hashPassword(password, saltHex) {
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits({
        name: 'PBKDF2',
        salt: fromHex(saltHex),
        iterations: PBKDF2_ITERATIONS,
        hash: 'SHA-256',
    }, key, 256);
    return toHex(bits);
}

function safeEqual(a, b) {
    if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

function headerValue(request, name) {
    const value = request.headers.get(name);
    if (!value) return '';
    const encoding = request.headers.get(name + '-Encoding') || '';
    if (encoding !== 'percent-utf-8') return value.trim();
    try {
        return decodeURIComponent(value);
    } catch (e) {
        return value;
    }
}

function clientToken(request) {
    const authorization = request.headers.get('authorization') || '';
    if (authorization.toLowerCase().startsWith('bearer ')) return authorization.substring(7).trim();
    return headerValue(request, 'x-webhtv-token');
}

function hkey(uid, configKey) {
    return Promise.resolve().then(async function () {
        const config = await sha256hex(configKey || '_default');
        return 'r_' + uid + '_' + config.substring(0, 24) + '_';
    });
}

function recordKey(prefix, historyKey) {
    return sha256hex(historyKey).then(function (hash) {
        return prefix + hash.substring(0, 48);
    });
}

async function issueToken(kv, uid) {
    const token = randomHex(32);
    const hash = await sha256hex(token);
    const expiresAt = Date.now() + TOKEN_TTL_MS;
    await kv.put('t_' + hash, JSON.stringify({uid: uid, exp: expiresAt}));
    await kv.put('ut_' + uid + '_' + hash, hash);
    return token;
}

async function authUser(kv, request) {
    const token = clientToken(request);
    if (!token) return null;
    const hash = await sha256hex(token);
    const raw = await kv.get('t_' + hash);
    if (!raw) return null;
    let entry;
    try {
        entry = JSON.parse(raw);
    } catch (e) {
        return null;
    }
    if (!entry || !entry.uid || entry.exp <= Date.now()) {
        await kv.delete('t_' + hash);
        return null;
    }
    const rawUser = await kv.get('u_' + entry.uid);
    if (!rawUser) return null;
    try {
        return JSON.parse(rawUser);
    } catch (e) {
        return null;
    }
}

async function readJson(request) {
    const text = await request.text();
    if (!text) return {};
    if (text.length > MAX_BODY_BYTES) throw new Error('body too large');
    return JSON.parse(text);
}

function validUsername(value) {
    return typeof value === 'string' && /^[A-Za-z0-9_.@-]{3,32}$/.test(value);
}

function validPassword(value) {
    return typeof value === 'string' && value.length >= 6 && value.length <= 64;
}

function generateUid() {
    return Date.now() * 1000 + Math.floor(Math.random() * 1000);
}

async function register(kv, request) {
    const body = await readJson(request);
    const username = (body.username || '').trim();
    const password = body.password || '';
    if (!validUsername(username)) return fail(400, '用户名需为 3-32 位字母、数字或 _ . @ -');
    if (!validPassword(password)) return fail(400, '密码长度需为 6-64 位');
    const indexKey = 'un_' + await sha256hex(username.toLowerCase());
    if (await kv.get(indexKey)) return fail(409, '用户名已存在');
    let uid = 0;
    for (let i = 0; i < 5; i++) {
        uid = generateUid();
        if (!(await kv.get('u_' + uid))) break;
    }
    const salt = randomHex(16);
    const hash = await hashPassword(password, salt);
    const user = {uid: uid, username: username, salt: salt, hash: hash, createdAt: Date.now()};
    await kv.put('u_' + uid, JSON.stringify(user));
    await kv.put(indexKey, String(uid));
    const token = await issueToken(kv, uid);
    return ok({uid: uid, name: username, token: token});
}

async function login(kv, request) {
    const body = await readJson(request);
    const username = (body.username || '').trim();
    const password = body.password || '';
    if (!username || !password) return fail(400, '请输入用户名和密码');
    const uid = await kv.get('un_' + await sha256hex(username.toLowerCase()));
    if (!uid) return fail(401, '用户名或密码错误');
    const raw = await kv.get('u_' + uid);
    if (!raw) return fail(401, '用户名或密码错误');
    let user;
    try {
        user = JSON.parse(raw);
    } catch (e) {
        return fail(401, '用户名或密码错误');
    }
    const hash = await hashPassword(password, user.salt);
    if (!safeEqual(hash, user.hash)) return fail(401, '用户名或密码错误');
    const token = await issueToken(kv, user.uid);
    return ok({uid: user.uid, name: user.username, token: token});
}

async function logout(kv, request) {
    const token = clientToken(request);
    if (!token) return ok({});
    const hash = await sha256hex(token);
    const raw = await kv.get('t_' + hash);
    if (raw) {
        try {
            const entry = JSON.parse(raw);
            if (entry && entry.uid) await kv.delete('ut_' + entry.uid + '_' + hash);
        } catch (e) {
            // ignore
        }
        await kv.delete('t_' + hash);
    }
    return ok({});
}

async function me(user) {
    return ok({uid: user.uid, name: user.username});
}

async function changePassword(kv, user, request) {
    const body = await readJson(request);
    const oldPassword = body.oldPassword || '';
    const newPassword = body.newPassword || '';
    if (!validPassword(newPassword)) return fail(400, '新密码长度需为 6-64 位');
    const oldHash = await hashPassword(oldPassword, user.salt);
    if (!safeEqual(oldHash, user.hash)) return fail(401, '原密码不正确');
    const salt = randomHex(16);
    const hash = await hashPassword(newPassword, salt);
    user.salt = salt;
    user.hash = hash;
    await kv.put('u_' + user.uid, JSON.stringify(user));
    let cursor;
    for (let page = 0; page < 4; page++) {
        const result = await kv.list({prefix: 'ut_' + user.uid + '_', limit: 256, cursor: cursor});
        for (const item of result.keys) {
            const tokenHash = await kv.get(item.key);
            if (tokenHash) await kv.delete('t_' + tokenHash);
            await kv.delete(item.key);
        }
        cursor = result.cursor;
        if (!cursor || result.complete) break;
    }
    const token = await issueToken(kv, user.uid);
    return ok({uid: user.uid, name: user.username, token: token});
}

async function deletePrefix(kv, prefix) {
    let cursor;
    for (let page = 0; page < 20; page++) {
        const result = await kv.list({prefix: prefix, limit: 256, cursor: cursor});
        if (!result || !result.keys || !result.keys.length) break;
        for (const item of result.keys) await kv.delete(item.key);
        cursor = result.cursor;
        if (!cursor || result.complete) break;
    }
}

/** 注销账号：校验密码后清空该用户全部云端数据（用户经确认选择彻底删除）。 */
async function deleteAccount(kv, user, request) {
    const body = await readJson(request);
    const hash = await hashPassword(body.password || '', user.salt);
    if (!safeEqual(hash, user.hash)) return fail(401, '密码不正确，无法注销');
    const indexKey = 'un_' + await sha256hex(String(user.username).toLowerCase());
    await kv.delete(indexKey);
    await kv.delete('u_' + user.uid);
    await deletePrefix(kv, 'ut_' + user.uid + '_');
    await deletePrefix(kv, 'bk_' + user.uid + '_');
    await deletePrefix(kv, 'r_' + user.uid + '_');
    return ok({uid: user.uid, deleted: true});
}

function appIdOf(request) {
    return headerValue(request, 'x-webhtv-app') || APP_ID;
}

function isAdmin(request, body) {
    const code = headerValue(request, 'x-webhtv-admin') || (body && body.adminCode) || '';
    return safeEqual(String(code), ADMIN_CODE);
}

function validType(value) {
    return typeof value === 'string' && /^[A-Za-z0-9_-]{1,32}$/.test(value);
}

function publicName(value) {
    if (typeof value !== 'string') return '';
    const key = value.trim();
    if (!/^[A-Za-z0-9._/-]{1,160}$/.test(key) || key.indexOf('..') >= 0) return '';
    return key;
}

async function saveBackup(kv, user, request) {
    const body = await readJson(request);
    const type = body.type;
    if (!validType(type)) return fail(400, '备份类型不合法');
    const record = {
        type: type,
        appId: appIdOf(request),
        updatedAt: Number(body.updatedAt || Date.now()),
        data: body.data == null ? null : body.data,
    };
    await kv.put('bk_' + user.uid + '_' + type, JSON.stringify(record));
    return ok({type: type, updatedAt: record.updatedAt});
}

async function loadBackup(kv, user, request) {
    const type = new URL(request.url).searchParams.get('type') || '';
    if (!validType(type)) return fail(400, '备份类型不合法');
    const raw = await kv.get('bk_' + user.uid + '_' + type);
    if (!raw) return fail(404, '暂无云端备份');
    try {
        return ok(JSON.parse(raw));
    } catch (e) {
        return fail(500, '云端备份数据损坏');
    }
}

async function listPublic(kv, request) {
    const prefix = PUBLIC_PREFIX + publicName(new URL(request.url).searchParams.get('prefix') || '');
    const keys = [];
    let cursor;
    for (let page = 0; page < 4; page++) {
        const result = await kv.list({prefix: prefix, limit: 256, cursor: cursor});
        if (!result || !result.keys) break;
        for (const item of result.keys) keys.push(item.key.substring(PUBLIC_PREFIX.length));
        cursor = result.cursor;
        if (!cursor || result.complete) break;
    }
    return ok({keys: keys});
}

async function getPublic(kv, request) {
    const key = publicName(new URL(request.url).searchParams.get('key') || '');
    if (!key) return fail(400, '公共资源名称不合法');
    const raw = await kv.get(PUBLIC_PREFIX + key);
    if (!raw) return fail(404, '公共资源不存在');
    try {
        return ok(JSON.parse(raw));
    } catch (e) {
        return ok({key: key, content: raw});
    }
}

async function putPublic(kv, request) {
    const body = await readJson(request);
    if (!isAdmin(request, body)) return fail(403, '需要管理员权限');
    const key = publicName(body.key);
    if (!key) return fail(400, '公共资源名称不合法');
    const record = {
        key: key,
        content: body.content == null ? '' : String(body.content),
        appId: appIdOf(request),
        updatedAt: Date.now(),
        updatedBy: body.updatedBy == null ? '' : String(body.updatedBy),
    };
    await kv.put(PUBLIC_PREFIX + key, JSON.stringify(record));
    return ok({key: key, updatedAt: record.updatedAt});
}

async function deletePublic(kv, request) {
    const body = await readJson(request);
    if (!isAdmin(request, body)) return fail(403, '需要管理员权限');
    const key = publicName(body.key);
    if (!key) return fail(400, '公共资源名称不合法');
    await kv.delete(PUBLIC_PREFIX + key);
    return ok({key: key});
}

function unwrapEvent(value) {
    if (value && typeof value === 'object') {
        if (value.data && typeof value.data === 'object') return value.data;
        if (value.record && typeof value.record === 'object') return value.record;
    }
    return value;
}

function isDeleteEvent(record) {
    if (!record) return false;
    if (record.deleted === true) return true;
    const event = String(record.event || '').toLowerCase();
    const action = String(record.action || record.op || '').toLowerCase();
    if (event === 'playback.deleted' || event.endsWith('.deleted')) return true;
    return action === 'delete' || action === 'deleted' || action === 'remove';
}

async function ingestEvents(kv, user, request) {
    let body;
    try {
        body = await readJson(request);
    } catch (e) {
        return fail(400, '请求体不是合法 JSON');
    }
    let items = [];
    if (Array.isArray(body)) items = body;
    else if (body && Array.isArray(body.items)) items = body.items;
    else if (body && Array.isArray(body.changes)) items = body.changes;
    else items = [body];
    items = items.slice(0, MAX_EVENTS_PER_REQUEST).map(unwrapEvent).filter(function (item) {
        return item && typeof item === 'object' && item.historyKey;
    });
    if (!items.length) return fail(400, '缺少 historyKey');
    const headerConfigKey = headerValue(request, 'x-webhtv-config-key');
    let accepted = 0;
    let skipped = 0;
    for (const item of items) {
        const configKey = item.configKey || headerConfigKey || '';
        const prefix = await hkey(user.uid, configKey);
        const key = await recordKey(prefix, item.historyKey);
        const updatedAt = Number(item.updatedAt || item.timestamp || item.deletedAt || Date.now());
        const existingRaw = await kv.get(key);
        let existing = null;
        if (existingRaw) {
            try {
                existing = JSON.parse(existingRaw);
            } catch (e) {
                existing = null;
            }
        }
        if (existing && Number(existing.updatedAt || 0) > updatedAt) {
            skipped++;
            continue;
        }
        let stored;
        if (isDeleteEvent(item)) {
            stored = {
                historyKey: item.historyKey,
                siteKey: item.siteKey || '',
                vodId: item.vodId || '',
                episodeName: item.episodeName || '',
                configKey: configKey,
                deleted: true,
                deletedAt: Number(item.deletedAt || updatedAt),
                updatedAt: updatedAt,
            };
        } else {
            stored = Object.assign({}, item, {
                configKey: configKey,
                deleted: false,
                updatedAt: updatedAt,
            });
        }
        await kv.put(key, JSON.stringify(stored));
        accepted++;
    }
    return ok({accepted: accepted, skipped: skipped});
}

async function pullChanges(kv, user, request) {
    const url = new URL(request.url);
    const configKey = headerValue(request, 'x-webhtv-config-key') || url.searchParams.get('configKey') || '';
    const since = Number(headerValue(request, 'x-webhtv-since') || 0) || 0;
    const requested = Number(headerValue(request, 'x-webhtv-limit') || 100) || 100;
    const limit = Math.min(200, Math.max(1, requested));
    const prefix = await hkey(user.uid, configKey);
    const keys = [];
    let cursor;
    for (let page = 0; page < 4; page++) {
        const result = await kv.list({prefix: prefix, limit: 256, cursor: cursor});
        if (!result || !result.keys) break;
        for (const item of result.keys) keys.push(item.key);
        cursor = result.cursor;
        if (!cursor || result.complete) break;
    }
    const records = [];
    for (const key of keys) {
        const raw = await kv.get(key);
        if (!raw) continue;
        let record;
        try {
            record = JSON.parse(raw);
        } catch (e) {
            continue;
        }
        if (!record || Number(record.updatedAt || 0) <= since) continue;
        records.push(record);
    }
    records.sort(function (a, b) {
        return Number(a.updatedAt || 0) - Number(b.updatedAt || 0);
    });
    const page = records.slice(0, limit);
    const changes = [];
    const deleted = [];
    let nextSince = since;
    for (const record of page) {
        const updatedAt = Number(record.updatedAt || 0);
        nextSince = Math.max(nextSince, updatedAt);
        if (record.deleted) deleted.push({
            historyKey: record.historyKey,
            siteKey: record.siteKey || '',
            vodId: record.vodId || '',
            episodeName: record.episodeName || '',
            configKey: record.configKey || configKey,
            deletedAt: record.deletedAt || updatedAt,
            updatedAt: updatedAt,
        });
        else changes.push(record);
    }
    return ok({changes: changes, deleted: deleted, nextSince: String(nextSince)});
}

async function route(context) {
    const request = context.request;
    if (request.method === 'OPTIONS') return new Response(null, {status: 204, headers: JSON_HEADERS});
    const kv = kvOf(context);
    if (!kv) return fail(500, 'KV 未绑定：请把命名空间绑定为 my_kv');
    const path = new URL(request.url).pathname.replace(/\/+$/, '') || '/';
    try {
        if (path === '/api/auth/register' && request.method === 'POST') return await register(kv, request);
        if (path === '/api/auth/login' && request.method === 'POST') return await login(kv, request);
        if (path === '/api/auth/logout' && request.method === 'POST') return await logout(kv, request);
        if (path === '/api/auth/password' && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await changePassword(kv, user, request);
        }
        if (path === '/api/auth/me' && request.method === 'GET') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await me(user);
        }
        if (path === '/api/auth/delete' && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await deleteAccount(kv, user, request);
        }
        if ((path === '/api/playback/events' || path === '/api/playback/push') && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await ingestEvents(kv, user, request);
        }
        if (path === '/api/playback/sync' && request.method === 'GET') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await pullChanges(kv, user, request);
        }
        if (path === '/api/backup/save' && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await saveBackup(kv, user, request);
        }
        if (path === '/api/backup/load' && request.method === 'GET') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await loadBackup(kv, user, request);
        }
        if (path === '/api/public/list' && request.method === 'GET') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await listPublic(kv, request);
        }
        if (path === '/api/public/get' && request.method === 'GET') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await getPublic(kv, request);
        }
        if (path === '/api/public/put' && request.method === 'POST') return await putPublic(kv, request);
        if (path === '/api/public/delete' && request.method === 'POST') return await deletePublic(kv, request);
        if (path === '/' || path === '/api' || path === '/api/health') {
            return ok({service: 'webhtv-cloud', time: Date.now()});
        }
        return fail(404, '接口不存在');
    } catch (e) {
        return fail(500, e && e.message ? e.message : '服务器内部错误');
    }
}

export default async function onRequest(context) {
    return route(context);
}

export {onRequest};
