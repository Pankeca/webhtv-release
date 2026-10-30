package com.fongmi.android.tv.api;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * 弹幕来源专用的网络通道。
 *
 * <p>为什么不用全局 {@link com.github.catvod.net.OkHttp}：
 * <ul>
 *   <li>弹幕源大多是个人公益服务，响应慢（实测单个来源首包 8~10s、弹幕 XML 可达 4MB），
 *       需要比播放器更宽松的超时；</li>
 *   <li>其中不少源用 IP 直连 + 自签证书（证书 CN 与 IP 不匹配），标准校验必然握手失败。
 *       这类源由 {@link com.fongmi.android.tv.bean.DanmakuSource#isInsecure()} 显式标记，
 *       只有被标记的来源才走宽松校验，其余仍走标准校验；</li>
 *   <li>弹幕请求是纯文本、不携带任何账号信息，风险面可控。</li>
 * </ul>
 */
public final class DanmakuHttp {

    private static final List<Call> ACTIVE = new ArrayList<>();

    private static volatile OkHttpClient strict;
    private static volatile OkHttpClient loose;

    private DanmakuHttp() {
    }

    public static OkHttpClient client(boolean insecure) {
        return insecure ? loose() : strict();
    }

    public static Call newCall(boolean insecure, Request request) {
        Call call = client(insecure).newCall(request);
        synchronized (ACTIVE) {
            ACTIVE.add(call);
        }
        return call;
    }

    public static void cancelAll() {
        synchronized (ACTIVE) {
            for (Call call : ACTIVE) call.cancel();
            ACTIVE.clear();
        }
    }

    private static OkHttpClient strict() {
        if (strict == null) {
            synchronized (DanmakuHttp.class) {
                if (strict == null) strict = builder().build();
            }
        }
        return strict;
    }

    private static OkHttpClient loose() {
        if (loose == null) {
            synchronized (DanmakuHttp.class) {
                if (loose == null) {
                    OkHttpClient.Builder builder = builder();
                    try {
                        X509TrustManager manager = new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        };
                        SSLContext context = SSLContext.getInstance("TLS");
                        context.init(null, new TrustManager[]{manager}, new java.security.SecureRandom());
                        builder.sslSocketFactory(context.getSocketFactory(), manager);
                        builder.hostnameVerifier((hostname, session) -> true);
                    } catch (Exception ignored) {
                    }
                    loose = builder.build();
                }
            }
        }
        return loose;
    }

    private static OkHttpClient.Builder builder() {
        return new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true);
    }
}
