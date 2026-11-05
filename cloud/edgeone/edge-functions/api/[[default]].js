// WebHTV cloud backend for EdgeOne Pages Edge Functions.
// Routes:
//   POST /api/auth/register | /api/auth/login | /api/auth/logout | /api/auth/password
//   GET  /api/auth/me
//   POST /api/playback/events   (used as the playback webhook)
//   GET  /api/playback/sync     (pull changes since cursor)
//   POST /api/backup/save       (snapshot backup: keeps / update tracks)
//   GET  /api/backup/load?type=keep
//   GET  /api/public/list       (shared folder, anonymous)
//   GET  /api/public/get?key=   (shared folder, anonymous)
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
// 拉取并发的单 key 读取上限：历史坏 blob 会拖慢整请求，所以不能逐条串行 get。
const READ_CONCURRENCY = 24;
// 单 key 读取超时：超过该值视为坏 key 或瞬时限流，不阻塞整请求；下次拉取会重试。
// 取 5s 是为了避免把「偶发变慢的有效记录」误判为坏 key（被跳过会推迟该条同步）。
const READ_TIMEOUT_MS = 5000;
// 记录内容读取走 CDN 缓存的 eventual 读：分区拉取只做增量合并，允许分钟级延迟以换取吞吐；
// 写入/鉴权仍使用 strong 读，保证读己之写与令牌时效。
const READ_CONSISTENCY = 'eventual';

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
        // 仅用于分区记录的全量拉取：eventual 读走 CDN 缓存，避免 100+ 次 strong 读串行/饱和
        getFast: async function (key) {
            const value = await blobStore().get(key, {type: 'text', consistency: READ_CONSISTENCY});
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

/** 单 key 读取加超时，避免单个坏 blob 拖住整个请求。 */
async function getWithTimeout(kv, key) {
    let timer;
    const timeout = new Promise(function (_, reject) {
        timer = setTimeout(function () {
            reject(new Error('timeout after ' + READ_TIMEOUT_MS + 'ms'));
        }, READ_TIMEOUT_MS);
    });
    try {
        return await Promise.race([kv.getFast(key), timeout]);
    } finally {
        clearTimeout(timer);
    }
}

/** 有并发上限的批量执行，结果按输入顺序返回。 */
async function mapLimit(items, limit, worker) {
    const results = new Array(items.length);
    let next = 0;
    async function runner() {
        while (next < items.length) {
            const index = next++;
            results[index] = await worker(items[index], index);
        }
    }
    const runners = [];
    const size = Math.min(limit, items.length);
    for (let i = 0; i < size; i++) runners.push(runner());
    await Promise.all(runners);
    return results;
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
    // 令牌条目仍 strong 读（撤销立即生效）；用户资料只读展示字段，允许 eventual 读降延迟
    const rawUser = await kv.getFast('u_' + entry.uid);
    if (!rawUser) return null;
    try {
        return JSON.parse(rawUser);
    } catch (e) {
        return null;
    }
}

/** 仅校验令牌并取 uid：分区拉取只需要 uid，省掉一次用户资料读取。 */
async function authUid(kv, request) {
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
    return entry.uid;
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

// ---------- 账号版本纪元 bk_<uid>_rv ----------
// 纪元只在「整份覆盖 / 清空账号数据 / 删除账号数据」时 +1；旧账号没有该 key，读作 rev=0，
// 老客户端既不发送也不解析该字段，因此新增纪元对存量客户端完全透明。

function revKey(uid) {
    return 'bk_' + uid + '_rv';
}

async function getBackupRev(kv, uid) {
    const raw = await kv.get(revKey(uid));
    if (!raw) return {rev: 0, updatedAt: 0};
    try {
        const parsed = JSON.parse(raw);
        const rev = Number(parsed && parsed.rev);
        return {
            rev: Number.isFinite(rev) && rev > 0 ? Math.floor(rev) : 0,
            updatedAt: Number((parsed && parsed.updatedAt) || 0) || 0,
        };
    } catch (e) {
        return {rev: 0, updatedAt: 0};
    }
}

async function bumpBackupRev(kv, uid) {
    const current = await getBackupRev(kv, uid);
    const next = {rev: current.rev + 1, updatedAt: Date.now()};
    await kv.put(revKey(uid), JSON.stringify(next));
    return next;
}

// ---------- keep 快照按条目合并 ----------
// 两端客户端上传的是整份 {keeps, tracks} 快照，服务端在 merge=1 时按「剧名优先、归一 key 兜底」
// 求并集，同一剧取 createTime/time 较新者，并保留墓碑（tombstones 或 deleted:true 条目），
// 避免先读后写的竞态把另一端的条目整份盖掉。

const KEEP_SYMBOL = '@@@';
const KEEP_TOMBSTONE_LIMIT = 500;
const KEEP_MERGE_RETRIES = 3;

function isObject(value) {
    return value != null && typeof value === 'object' && !Array.isArray(value);
}

function textOf(value) {
    return value == null ? '' : String(value).trim();
}

/** key 归一为 siteKey@@@vodId@@@0：第三段 cid 只在本机有意义，跨端身份忽略。 */
function canonicalKeepKey(key) {
    const parts = textOf(key).split(KEEP_SYMBOL);
    if (parts.length >= 2 && parts[0] && parts[1]) return parts[0] + KEEP_SYMBOL + parts[1] + KEEP_SYMBOL + '0';
    return textOf(key);
}

/** 合并身份：剧名优先（与客户端「同名只留一条」口径一致），无名时退回归一 key。 */
function keepIdentity(item) {
    if (!isObject(item)) return '';
    const name = textOf(item.vodName);
    if (name) return 'n:' + name;
    const key = canonicalKeepKey(item.key);
    if (key) return 'k:' + key;
    const siteKey = textOf(item.siteKey);
    const vodId = textOf(item.vodId);
    if (siteKey && vodId) return 'k:' + siteKey + KEEP_SYMBOL + vodId + KEEP_SYMBOL + '0';
    return '';
}

function keepTime(item) {
    if (!isObject(item)) return 0;
    const value = item.createTime != null ? item.createTime : item.time;
    return Number(value) || 0;
}

function normalizeKeep(item) {
    const copy = Object.assign({}, item);
    copy.key = canonicalKeepKey(item.key);
    return copy;
}

function collectTombstones(map, list, fallbackTime, onlyDeleted) {
    if (!Array.isArray(list)) return;
    for (const entry of list) {
        if (!isObject(entry)) continue;
        if (onlyDeleted && entry.deleted !== true) continue;
        const id = keepIdentity(entry);
        if (!id) continue;
        const deletedAt = Number(entry.deletedAt || entry.updatedAt || entry.time || entry.createTime || fallbackTime) || 0;
        if (deletedAt <= 0) continue;
        map.set(id, Math.max(map.get(id) || 0, deletedAt));
    }
}

/** 合并两份 keep 快照：并集 + 同剧取新 + 墓碑删除，输出可重复执行（幂等）。 */
function mergeKeepData(baseData, incomingData) {
    const base = isObject(baseData) ? baseData : {};
    const incoming = isObject(incomingData) ? incomingData : {};
    const now = Date.now();
    const tombstones = new Map();
    collectTombstones(tombstones, base.tombstones, 0, false);
    collectTombstones(tombstones, incoming.tombstones, now, false);
    // 兼容「条目自带 deleted:true」的墓碑表达（老快照 + 新客户端都可能是这种）
    collectTombstones(tombstones, base.keeps, 0, true);
    collectTombstones(tombstones, base.tracks, 0, true);
    collectTombstones(tombstones, incoming.keeps, now, true);
    collectTombstones(tombstones, incoming.tracks, now, true);

    const keepMap = new Map();
    const trackMap = new Map();
    const deletedAtOf = (id) => tombstones.get(id) || 0;

    const mergeKeep = (item) => {
        if (!isObject(item) || item.deleted === true) return;
        const id = keepIdentity(item);
        if (!id) return;
        const entry = normalizeKeep(item);
        if (keepTime(entry) <= deletedAtOf(id)) return; // 墓碑之后才允许出现，删除不复活
        const old = keepMap.get(id);
        if (!old || keepTime(entry) > keepTime(old)) keepMap.set(id, entry);
    };
    const mergeTrack = (item) => {
        if (!isObject(item) || item.deleted === true) return;
        const id = keepIdentity(item);
        if (!id) return;
        const entry = normalizeKeep(item);
        if (Number(entry.time || 0) <= deletedAtOf(id)) return;
        const old = trackMap.get(id);
        if (!old) {
            trackMap.set(id, entry);
            return;
        }
        // 进度只增不减（seen/count 取大），展示字段取 time 较新一侧，days=-1 视为「未设置」
        const newer = Number(entry.time || 0) >= Number(old.time || 0) ? entry : old;
        const merged = Object.assign({}, newer);
        const seen = Math.max(Number(old.seen) || 0, Number(entry.seen) || 0);
        const count = Math.max(Number(old.count) || 0, Number(entry.count) || 0, seen);
        merged.seen = seen;
        merged.count = count;
        const days = Number(entry.days);
        merged.days = Number.isFinite(days) && days !== -1 ? days : (Number(old.days) || 0);
        merged.time = Math.max(Number(old.time) || 0, Number(entry.time) || 0);
        trackMap.set(id, merged);
    };

    for (const item of Array.isArray(base.keeps) ? base.keeps : []) mergeKeep(item);
    for (const item of Array.isArray(incoming.keeps) ? incoming.keeps : []) mergeKeep(item);
    for (const item of Array.isArray(base.tracks) ? base.tracks : []) mergeTrack(item);
    for (const item of Array.isArray(incoming.tracks) ? incoming.tracks : []) mergeTrack(item);

    const tombstoneList = Array.from(tombstones.entries())
        .sort(function (a, b) { return b[1] - a[1]; })
        .slice(0, KEEP_TOMBSTONE_LIMIT)
        .map(function (pair) {
            const id = pair[0];
            const name = id.startsWith('n:') ? id.substring(2) : '';
            return {
                key: id.startsWith('k:') ? id.substring(2) : '',
                vodName: name,
                deletedAt: pair[1],
            };
        });

    return {
        keeps: Array.from(keepMap.values()),
        tracks: Array.from(trackMap.values()),
        tombstones: tombstoneList,
    };
}

/**
 * 回读校验：合并结果里的每条身份、时间与进度都还在云端。
 * Blob 没有 CAS/事务，只能靠「写后延时回读」发现被并发写覆盖，再合并一轮收敛。
 */
function mergedFullyStored(raw, merged) {
    try {
        const stored = JSON.parse(raw);
        if (!isObject(stored) || !isObject(stored.data)) return false;
        const keeps = new Map();
        for (const item of Array.isArray(stored.data.keeps) ? stored.data.keeps : []) {
            const id = keepIdentity(item);
            if (id) keeps.set(id, item);
        }
        const tracks = new Map();
        for (const item of Array.isArray(stored.data.tracks) ? stored.data.tracks : []) {
            const id = keepIdentity(item);
            if (id) tracks.set(id, item);
        }
        for (const item of Array.isArray(merged.keeps) ? merged.keeps : []) {
            const id = keepIdentity(item);
            if (!id) continue;
            const found = keeps.get(id);
            if (!found || keepTime(found) < keepTime(item)) return false;
        }
        for (const item of Array.isArray(merged.tracks) ? merged.tracks : []) {
            const id = keepIdentity(item);
            if (!id) continue;
            const found = tracks.get(id);
            if (!found) return false;
            if ((Number(found.seen) || 0) < (Number(item.seen) || 0)) return false;
            if ((Number(found.count) || 0) < (Number(item.count) || 0)) return false;
        }
        return true;
    } catch (e) {
        return false;
    }
}

function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function wantsMerge(request, body) {
    if (body && (body.merge === true || body.merge === 1 || body.merge === '1' || body.merge === 'true')) return true;
    return new URL(request.url).searchParams.get('merge') === '1';
}

/** 注销账号：校验密码后清空该用户全部云端数据（用户经确认选择彻底删除）。 */
async function deleteAccount(kv, user, request) {
    const body = await readJson(request);
    const hash = await hashPassword(body.password || '', user.salt);
    if (!safeEqual(hash, user.hash)) return fail(401, '密码不正确，无法注销');
    const epoch = await bumpBackupRev(kv, user.uid);
    const indexKey = 'un_' + await sha256hex(String(user.username).toLowerCase());
    await kv.delete(indexKey);
    await kv.delete('u_' + user.uid);
    await deletePrefix(kv, 'ut_' + user.uid + '_');
    await deletePrefix(kv, 'bk_' + user.uid + '_');
    await deletePrefix(kv, 'r_' + user.uid + '_');
    // 账号已不存在，仅保留纪元墓碑：uid 的 rev 永不复用、不回退
    await kv.put(revKey(user.uid), JSON.stringify({rev: epoch.rev, updatedAt: epoch.updatedAt, deleted: true}));
    return ok({uid: user.uid, deleted: true, rev: epoch.rev});
}

/** 清空账号数据：删个人备份与播放历史，保留账号本身，纪元 +1，供「清空后全量重同步」识别。 */
async function clearAccountData(kv, user) {
    const epoch = await bumpBackupRev(kv, user.uid);
    // deletePrefix 会把 bk_<uid>_rv 一起删掉，所以纪元必须在删完后重新写回
    await deletePrefix(kv, 'bk_' + user.uid + '_');
    await deletePrefix(kv, 'r_' + user.uid + '_');
    await kv.put(revKey(user.uid), JSON.stringify(epoch));
    return ok({uid: user.uid, cleared: true, rev: epoch.rev, updatedAt: epoch.updatedAt});
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
    // rv 是纪元保留名，不允许作为备份类型写入
    if (!validType(type) || type === 'rv') return fail(400, '备份类型不合法');
    // 仅 keep 支持 merge=1；其它类型保持原有的整包覆盖行为不变
    if (type === 'keep' && wantsMerge(request, body)) {
        const revInfo = await getBackupRev(kv, user.uid);
        const key = 'bk_' + user.uid + '_' + type;
        const incomingData = isObject(body.data) ? body.data : {};
        let last = null;
        for (let attempt = 0; attempt < KEEP_MERGE_RETRIES; attempt++) {
            const raw = await kv.get(key);
            let base = null;
            if (raw) {
                try {
                    base = JSON.parse(raw);
                } catch (e) {
                    base = null;
                }
            }
            const merged = mergeKeepData(base && base.data, incomingData);
            const record = {
                type: type,
                appId: appIdOf(request),
                updatedAt: Date.now(),
                data: merged,
            };
            await kv.put(key, JSON.stringify(record));
            last = record;
            // 没有 CAS/事务：写完延时回读；内容被并发写覆盖就基于最新内容再合并一轮
            let stable = true;
            for (const delay of [0, 200, 450]) {
                if (delay > 0) await sleep(delay);
                const check = await kv.get(key);
                if (!check || !mergedFullyStored(check, merged)) {
                    stable = false;
                    break;
                }
            }
            if (stable) {
                return ok({type: type, updatedAt: record.updatedAt, rev: revInfo.rev, merged: true, attempts: attempt + 1});
            }
        }
        return ok({type: type, updatedAt: last.updatedAt, rev: revInfo.rev, merged: true, attempts: KEEP_MERGE_RETRIES, partial: true});
    }
    const revInfo = await bumpBackupRev(kv, user.uid);
    const record = {
        type: type,
        appId: appIdOf(request),
        updatedAt: Number(body.updatedAt || Date.now()),
        data: body.data == null ? null : body.data,
    };
    await kv.put('bk_' + user.uid + '_' + type, JSON.stringify(record));
    return ok({type: type, updatedAt: record.updatedAt, rev: revInfo.rev});
}

async function loadBackup(kv, user, request) {
    const type = new URL(request.url).searchParams.get('type') || '';
    if (!validType(type) || type === 'rv') return fail(400, '备份类型不合法');
    const revInfo = await getBackupRev(kv, user.uid);
    const raw = await kv.get('bk_' + user.uid + '_' + type);
    // 404 仍按老格式返回；额外带上 rev，旧客户端只会读 ok/error，不会受影响
    if (!raw) return json(404, {ok: false, error: '暂无云端备份', rev: revInfo.rev, updatedAt: revInfo.updatedAt});
    try {
        return ok(Object.assign({}, JSON.parse(raw), {rev: revInfo.rev}));
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

async function pullChanges(kv, uid, request) {
    const url = new URL(request.url);
    const configKey = headerValue(request, 'x-webhtv-config-key') || url.searchParams.get('configKey') || '';
    const since = Number(headerValue(request, 'x-webhtv-since') || 0) || 0;
    const requested = Number(headerValue(request, 'x-webhtv-limit') || 100) || 100;
    const limit = Math.min(200, Math.max(1, requested));
    const prefix = await hkey(uid, configKey);
    const keys = [];
    let cursor;
    for (let page = 0; page < 4; page++) {
        const result = await kv.list({prefix: prefix, limit: 256, cursor: cursor});
        if (!result || !result.keys) break;
        for (const item of result.keys) keys.push(item.key);
        cursor = result.cursor;
        if (!cursor || result.complete) break;
    }
    // 并发读取 + 单 key 超时：历史坏 blob（COS 502）不再逐条串行重试拖慢整请求。
    // 不做持久/内存失败标记：EdgeOne 每次都重建 isolate，内存缓存跨请求无效，落盘缓存又要多一次读；
    // 每次请求重新探测的代价已被并发+超时压到最低，坏 blob 被修复后下一次请求即自动恢复。
    let logged = 0;
    const outcomes = await mapLimit(keys, READ_CONCURRENCY, async function (key) {
        try {
            return {key: key, raw: await getWithTimeout(kv, key)};
        } catch (e) {
            // 单条 blob 读取失败只跳过该条并记录，不能让整个增量接口 500；
            // 不删除坏 blob，避免影响尚未迁移的其他设备
            const reason = String((e && e.message) || e || 'unknown').slice(0, 200);
            if (logged < 8) {
                logged++;
                console.error('[playback-sync] kv.get failed: ' + key + ' -> ' + reason);
            }
            return {key: key, error: reason};
        }
    });
    const records = [];
    const skipped = [];
    for (const outcome of outcomes) {
        if (outcome.error) {
            skipped.push({key: outcome.key, error: outcome.error});
            continue;
        }
        if (!outcome.raw) continue;
        let record;
        try {
            record = JSON.parse(outcome.raw);
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
    // skipped/warnings 供客户端与排查使用；旧客户端会忽略这些新增可选字段
    return ok({
        changes: changes,
        deleted: deleted,
        nextSince: String(nextSince),
        skipped: skipped.length,
        warnings: skipped.slice(0, 5),
    });
}

const DANDAN_BASE = 'https://api.dandanplay.net';
const DANDAN_MATCH_PATH = '/api/v2/match';

function envValue(context, key) {
    if (context && context.env && context.env[key]) return String(context.env[key]);
    if (typeof process !== 'undefined' && process.env && process.env[key]) return String(process.env[key]);
    return '';
}

function base64Bytes(buffer) {
    let out = '';
    const bytes = new Uint8Array(buffer);
    for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return btoa(out);
}

/** 弹弹play 签名验证模式：base64(sha256(AppId + Timestamp + Path + AppSecret)) */
async function dandanHeaders(context, path) {
    const appId = envValue(context, 'DANDAN_APP_ID');
    const appSecret = envValue(context, 'DANDAN_APP_SECRET');
    if (!appId || !appSecret) return null;
    const timestamp = String(Math.floor(Date.now() / 1000));
    const raw = new TextEncoder().encode(appId + timestamp + path + appSecret);
    const digest = await crypto.subtle.digest('SHA-256', raw);
    return {
        'X-AppId': appId,
        'X-Timestamp': timestamp,
        'X-Signature': base64Bytes(digest),
        'Accept': 'application/json',
    };
}

async function danmakuMatch(context, request) {
    try {
        const body = await request.json().catch(() => ({}));
        const name = String(body.name || '').trim();
        const episode = String(body.episode || '').trim();
        if (!name) return json(200, []);
        const headers = await dandanHeaders(context, DANDAN_MATCH_PATH);
        // 未配置 AppId/AppSecret 时返回空数组，App 侧显示「暂无弹幕」而不是报错
        if (!headers) return json(200, []);
        const fileName = /\.[a-z0-9]{2,5}$/i.test(name + episode) ? `${name} ${episode}` : `${name} ${episode}.mp4`;
        const upstream = await fetch(DANDAN_BASE + DANDAN_MATCH_PATH, {
            method: 'POST',
            headers: {...headers, 'Content-Type': 'application/json'},
            body: JSON.stringify({fileName: fileName.replace(/\s+/g, ' ').trim()}),
        });
        if (!upstream.ok) return json(200, []);
        const data = await upstream.json().catch(() => null);
        const match = data && data.isMatched && data.matches && data.matches.length ? data.matches[0] : null;
        if (!match || !match.episodeId) return json(200, []);
        const origin = new URL(request.url).origin;
        const title = [match.animeTitle, match.episodeTitle].filter(Boolean).join(' ');
        return json(200, [{name: title || name, url: `${origin}/api/danmaku/comment/${match.episodeId}`}]);
    } catch (e) {
        return json(200, []);
    }
}

async function danmakuComment(context, request, episodeId) {
    if (!/^\d{1,20}$/.test(episodeId)) return fail(400, 'episodeId 无效');
    const headers = await dandanHeaders(context, `/api/v2/comment/${episodeId}`);
    if (!headers) return json(200, {comments: []});
    try {
        const query = new URL(request.url).search || '?withRelated=true&chConvert=1';
        const upstream = await fetch(`${DANDAN_BASE}/api/v2/comment/${episodeId}${query}`, {headers: headers});
        const text = await upstream.text();
        return new Response(text, {
            status: 200,
            headers: {
                'content-type': 'application/json; charset=utf-8',
                'cache-control': 'public, max-age=600',
                'access-control-allow-origin': '*',
            },
        });
    } catch (e) {
        return json(200, {comments: []});
    }
}

async function route(context) {
    const request = context.request;
    if (request.method === 'OPTIONS') return new Response(null, {status: 204, headers: JSON_HEADERS});
    const kv = kvOf(context);
    if (!kv) return fail(500, 'KV 未绑定：请把命名空间绑定为 my_kv');
    const path = new URL(request.url).pathname.replace(/\/+$/, '') || '/';
    try {
        if ((path === '/api/danmaku' || path === '/api/danmaku/match') && request.method === 'POST') {
            return await danmakuMatch(context, request);
        }
        if (path.startsWith('/api/danmaku/comment/') && request.method === 'GET') {
            return await danmakuComment(context, request, path.substring('/api/danmaku/comment/'.length));
        }
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
        if ((path === '/api/account/clear' || path === '/api/backup/clear') && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await clearAccountData(kv, user);
        }
        if ((path === '/api/playback/events' || path === '/api/playback/push') && request.method === 'POST') {
            const user = await authUser(kv, request);
            if (!user) return fail(401, '登录已失效');
            return await ingestEvents(kv, user, request);
        }
        if (path === '/api/playback/sync' && request.method === 'GET') {
            const uid = await authUid(kv, request);
            if (!uid) return fail(401, '登录已失效');
            return await pullChanges(kv, uid, request);
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
            return await listPublic(kv, request);
        }
        if (path === '/api/public/get' && request.method === 'GET') {
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
