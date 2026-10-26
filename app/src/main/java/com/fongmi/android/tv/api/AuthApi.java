package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
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

    public static String server() {
        return normalize(AccountStore.server());
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
        return Task.submit(() -> {
            String server = server();
            if (TextUtils.isEmpty(server)) {
                deliver(callback, null, "未配置云端服务地址");
                return;
            }
            try {
                Request.Builder builder = new Request.Builder().url(server + "/api/auth/" + action);
                if (token != null && !TextUtils.isEmpty(token)) builder.header("Authorization", "Bearer " + token);
                if ("POST".equals(method)) builder.post(RequestBody.create(body.toString(), JSON));
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

    private static JsonObject parse(String text) {
        try {
            return App.gson().fromJson(text, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String message(JsonObject json, int code) {
        if (json != null && json.has("error")) return json.get("error").getAsString();
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
