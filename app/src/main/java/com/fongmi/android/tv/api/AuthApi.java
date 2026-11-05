package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.AccountStore;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fongmi.android.tv.utils.Task;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AuthApi {

    private static final Pattern HOST_PATTERN = Pattern.compile("^(https?://[^/]+)", Pattern.CASE_INSENSITIVE);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface Callback {

        void success(JsonObject data);

        void error(String message);
    }

    /**
     * 共享配置条件写回调：把 409 冲突与 404 缺失单独暴露出来，
     * 避免编辑面板靠解析错误文案来判断该「重新加载」还是「新建」。
     */
    public interface MetaCallback {

        void success(JsonObject data);

        /** 409 REV_CONFLICT：current 为服务端最新记录（含 rev/content/updatedBy/updatedAt）。 */
        void conflict(JsonObject current);

        /** 404：记录不存在或已被彻底删除。 */
        void missing();

        void error(String message);
    }

    public static String server() {
        return normalize(AccountStore.effectiveServer());
    }

    public static void server(String server) {
        AccountStore.server(server);
    }

    public static String normalize(String server) {
        if (TextUtils.isEmpty(server)) return "";
        String value = server.trim();
        if (!value.startsWith("http")) value = "https://" + value;
        Matcher matcher = HOST_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static Future register(String username, String password, Callback callback) {
        return post("register", null, body(username, password, null), callback);
    }

    public static Future login(String username, String password, Callback callback) {
        return post("login", null, body(username, password, null), callback);
    }

    public static Future logout(Callback callback) {
        return post("logout", AccountStore.token(), new JsonObject(), callback);
    }

    public static Future me(Callback callback) {
        return get("me", callback);
    }

    public static Future changePassword(String oldPassword, String newPassword, Callback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("oldPassword", oldPassword);
        body.addProperty("newPassword", newPassword);
        return post("password", AccountStore.token(), body, callback);
    }

    /** 注销账号：需要再次输入密码，服务端会清除该用户全部云端数据。 */
    public static Future deleteAccount(String password, Callback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("password", password);
        return post("delete", AccountStore.token(), body, callback);
    }

    public static Future backupSave(String type, Object data, Callback callback) {
        return backupSave(type, data, true, callback);
    }

    /**
     * merge=true 时服务端按剧名+归一 key 求并集（当前仅 keep 类型支持），
     * 避免两端整包覆盖互相盖掉收藏/追更；用户明确选择「覆盖云端」时传 false。
     */
    public static Future backupSave(String type, Object data, boolean merge, Callback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("updatedAt", System.currentTimeMillis());
        if (merge) body.addProperty("merge", 1);
        body.add("data", App.gson().toJsonTree(data));
        return requestPath("POST", "/api/backup/save", AccountStore.token(), body, null, callback);
    }

    public static Future backupLoad(String type, Callback callback) {
        return requestPath("GET", "/api/backup/load?type=" + Uri.encode(type), AccountStore.token(), null, null, callback);
    }

    public static Future publicList(Callback callback) {
        return requestPath("GET", "/api/public/list", AccountStore.token(), null, null, callback);
    }

    /**
     * 按前缀列出共享配置 key；管理员加 includeDeleted=1 可看到软删除记录。
     * 站点注入超过 1MiB 会拆成 tvbox_shared_sites / _2 / _3…，需要用它枚举分片。
     */
    public static Future publicListMeta(String prefix, boolean includeDeleted, Callback callback) {
        StringBuilder path = new StringBuilder("/api/public/list");
        String value = prefix == null ? "" : prefix.trim();
        if (!TextUtils.isEmpty(value)) path.append("?prefix=").append(Uri.encode(value));
        if (includeDeleted) path.append(TextUtils.isEmpty(value) ? "?" : "&").append("includeDeleted=1");
        return requestPath("GET", path.toString(), AccountStore.token(), null,
                includeDeleted ? BuildConfig.ADMIN_CODE : null, callback);
    }

    /** 后端健康检查：确认当前云端服务地址可用。 */
    public static Future health(Callback callback) {
        return requestPath("GET", "/api/health", null, null, null, callback);
    }

    public static Future publicGet(String key, Callback callback) {
        return requestPath("GET", "/api/public/get?key=" + Uri.encode(key), AccountStore.token(), null, null, callback);
    }

    public static Future publicPut(String key, String content, Callback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("content", content);
        body.addProperty("updatedBy", AccountStore.name());
        return requestPath("POST", "/api/public/put", AccountStore.token(), body, BuildConfig.ADMIN_CODE, callback);
    }

    public static Future publicDelete(String key, Callback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        return requestPath("POST", "/api/public/delete", AccountStore.token(), body, BuildConfig.ADMIN_CODE, callback);
    }

    // ---------- 共享配置：rev 条件写 / 软删除 / 恢复 / 审计 ----------
    // 与 docs/共享配置编辑契约.md 及 TVBox 端 AuthApi 保持同一套语义。

    /** 读取共享配置完整记录（含 rev/deleted/contentHash）；includeDeleted 仅管理员可用。 */
    public static Future publicGetMeta(String key, boolean includeDeleted, MetaCallback callback) {
        String path = "/api/public/get?key=" + Uri.encode(key);
        if (includeDeleted) path += "&includeDeleted=1";
        // 普通拉取保持匿名只读；只有查看已删除记录时才带管理员头
        return requestMeta("GET", path, null, includeDeleted, callback);
    }

    /** 带 rev 的条件写：rev 不匹配服务端会返回 409，由调用方决定重新加载还是覆盖。 */
    public static Future publicPutConditional(String key, String content, long rev, String updatedBy,
                                              String reason, MetaCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("content", content);
        body.addProperty("rev", rev);
        body.addProperty("updatedBy", updatedBy);
        body.addProperty("reason", reason);
        return requestMeta("POST", "/api/public/put", body, true, callback);
    }

    /** 软删除（保留内容，可恢复）；返回新 rev。 */
    public static Future publicDeleteSoft(String key, long rev, String updatedBy, String reason,
                                          MetaCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("rev", rev);
        body.addProperty("updatedBy", updatedBy);
        body.addProperty("reason", reason);
        return requestMeta("POST", "/api/public/delete", body, true, callback);
    }

    /** 恢复软删除记录。 */
    public static Future publicRestore(String key, long rev, String updatedBy, String reason,
                                       MetaCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("rev", rev);
        body.addProperty("updatedBy", updatedBy);
        body.addProperty("reason", reason);
        return requestMeta("POST", "/api/public/restore", body, true, callback);
    }

    /** 彻底删除（只允许针对已软删除的记录，服务端会拒绝未删记录）。 */
    public static Future publicPurge(String key, long rev, String updatedBy, String reason,
                                     MetaCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("rev", rev);
        body.addProperty("updatedBy", updatedBy);
        body.addProperty("reason", reason);
        return requestMeta("POST", "/api/public/purge", body, true, callback);
    }

    /** 审计日志（管理员）：最近 N 条 put/delete/restore/purge 摘要。 */
    public static Future publicAudit(String key, int limit, Callback callback) {
        return requestPath("GET", "/api/public/audit?key=" + Uri.encode(key)
                        + "&limit=" + Math.max(1, Math.min(200, limit)),
                AccountStore.token(), null, BuildConfig.ADMIN_CODE, callback);
    }

    private static JsonObject body(String username, String password, String extra) {
        JsonObject object = new JsonObject();
        object.addProperty("username", username);
        object.addProperty("password", password);
        return object;
    }

    private static Future get(String action, Callback callback) {
        return request("GET", action, null, null, callback);
    }

    private static Future post(String action, String token, JsonObject body, Callback callback) {
        return request("POST", action, token, body, callback);
    }

    private static Future request(String method, String action, String token, JsonObject body, Callback callback) {
        return requestPath(method, "/api/auth/" + action, token, body, null, callback);
    }

    private static Future requestPath(String method, String path, String token, JsonObject body, String adminCode, Callback callback) {
        return Task.submit(() -> {
            String server = server();
            if (TextUtils.isEmpty(server)) {
                deliver(callback, null, "未配置云端服务地址");
                return;
            }
            try {
                Request.Builder builder = new Request.Builder().url(server + path);
                if (token != null && !TextUtils.isEmpty(token)) builder.header("Authorization", "Bearer " + token);
                if (!TextUtils.isEmpty(adminCode)) header(builder, "X-WebHTV-Admin", adminCode);
                if ("POST".equals(method)) builder.post(RequestBody.create(body == null ? "{}" : body.toString(), JSON));
                else builder.get();
                Call call = OkHttp.client().newCall(builder.build());
                try (Response response = call.execute()) {
                    String text = response.body() == null ? "" : response.body().string();
                    JsonObject json = parse(text);
                    if (json == null) {
                        deliver(callback, null, "服务响应异常(" + response.code() + ")");
                        return;
                    }
                    if (!response.isSuccessful() || !json.has("ok") || !json.get("ok").getAsBoolean()) {
                        deliver(callback, null, message(json, response.code()));
                        return;
                    }
                    deliver(callback, json, null);
                }
            } catch (Exception e) {
                deliver(callback, null, e.getMessage() == null ? "网络请求失败" : e.getMessage());
            }
        });
    }

    /**
     * 共享配置请求：统一解析 200 / 409(REV_CONFLICT) / 404 / 其它错误，
     * 让编辑面板不必再靠错误文案判断冲突或记录缺失。
     */
    private static Future requestMeta(String method, String path, JsonObject body, boolean admin,
                                      MetaCallback callback) {
        return Task.submit(() -> {
            String server = server();
            if (TextUtils.isEmpty(server)) {
                deliverMetaError(callback, "未配置云端服务地址");
                return;
            }
            try {
                Request.Builder builder = new Request.Builder().url(server + path);
                String token = AccountStore.token();
                if (!TextUtils.isEmpty(token)) builder.header("Authorization", "Bearer " + token);
                if (admin) header(builder, "X-WebHTV-Admin", BuildConfig.ADMIN_CODE);
                if ("POST".equals(method)) builder.post(RequestBody.create(body == null ? "{}" : body.toString(), JSON));
                else builder.get();
                Call call = OkHttp.client().newCall(builder.build());
                try (Response response = call.execute()) {
                    int code = response.code();
                    String text = response.body() == null ? "" : response.body().string();
                    JsonObject json = parse(text);
                    if (json == null) {
                        deliverMetaError(callback, "服务响应异常(" + code + ")");
                        return;
                    }
                    if (code == 409 && "REV_CONFLICT".equals(optString(json, "code"))) {
                        JsonObject current = json.has("current") && json.get("current").isJsonObject()
                                ? json.getAsJsonObject("current") : new JsonObject();
                        deliverMetaConflict(callback, current);
                        return;
                    }
                    if (code == 404) {
                        deliverMetaMissing(callback);
                        return;
                    }
                    if (!response.isSuccessful() || !json.has("ok") || !json.get("ok").getAsBoolean()) {
                        deliverMetaError(callback, message(json, code));
                        return;
                    }
                    deliverMetaSuccess(callback, json);
                }
            } catch (Exception e) {
                deliverMetaError(callback, e.getMessage() == null ? "网络请求失败" : e.getMessage());
            }
        });
    }

    private static String optString(JsonObject json, String key) {
        try {
            if (json != null && json.has(key) && !json.get(key).isJsonNull()) return json.get(key).getAsString();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void deliverMetaSuccess(MetaCallback callback, JsonObject data) {
        App.post(() -> {
            if (callback != null) callback.success(data);
        });
    }

    private static void deliverMetaConflict(MetaCallback callback, JsonObject current) {
        App.post(() -> {
            if (callback != null) callback.conflict(current);
        });
    }

    private static void deliverMetaMissing(MetaCallback callback) {
        App.post(() -> {
            if (callback != null) callback.missing();
        });
    }

    private static void deliverMetaError(MetaCallback callback, String error) {
        App.post(() -> {
            if (callback != null) callback.error(error);
        });
    }

    private static void header(Request.Builder builder, String name, String value) {
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            builder.header(name, value);
        } else {
            builder.header(name, Uri.encode(value));
            builder.header(name + "-Encoding", "percent-utf-8");
        }
    }

    private static JsonObject parse(String text) {
        try {
            return App.gson().fromJson(text, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String message(JsonObject json, int code) {
        // 旧接口用 error，共享配置新接口（如 409/503）用 message
        if (json != null && json.has("error") && !json.get("error").isJsonNull()) {
            return json.get("error").getAsString();
        }
        if (json != null && json.has("message") && !json.get("message").isJsonNull()) {
            return json.get("message").getAsString();
        }
        return "请求失败(" + code + ")";
    }

    private static void deliver(Callback callback, JsonObject data, String error) {
        App.post(() -> {
            if (callback == null) return;
            if (error != null) callback.error(error);
            else callback.success(data);
        });
    }
}
