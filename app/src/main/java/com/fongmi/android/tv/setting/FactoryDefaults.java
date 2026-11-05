package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 出厂默认配置：仅保留弹幕源。
 *
 * <p>站点注入与网页脚本规则已取消内置预设，改由管理员在云端配置中心发布，
 * 普通用户通过云端同步获取后才生效；本机不再自带任何站点/脚本资产。
 *
 * <p>弹幕源仍按「用户没有配置才播种」判断，天然幂等：
 * {@link DanmakuSourceSetting#hasCustomSources()} 为假时读取 assets 播种；
 * 用户把来源删空后存的是 {@code "[]"}，仍算「配过」，不会被重新塞回来。
 * 失败不写任何标记，下次启动自动重试。
 */
public final class FactoryDefaults {

    private static final String TAG = "factory";
    private static final String ASSET_DANMAKU = "factory/danmaku_sources.json";

    private FactoryDefaults() {
    }

    /** 启动时调用。幂等，可以每次启动都调。 */
    public static void applyIfNeeded() {
        seedDanmaku();
    }

    private static void seedDanmaku() {
        if (DanmakuSourceSetting.hasCustomSources()) return;
        List<DanmakuSource> sources = readDanmaku();
        if (sources == null || sources.isEmpty()) {
            SpiderDebug.log(TAG, "danmaku seed skipped: asset empty or unparsable");
            return;
        }
        try {
            DanmakuSourceSetting.saveAll(sources);
            SpiderDebug.log(TAG, "danmaku seeded count=%d", sources.size());
        } catch (Exception e) {
            SpiderDebug.log(TAG, "danmaku seed failed: %s", e);
        }
    }

    private static List<DanmakuSource> readDanmaku() {
        String json = readAsset(ASSET_DANMAKU);
        if (TextUtils.isEmpty(json)) return null;
        try {
            Type type = TypeToken.getParameterized(List.class, DanmakuSource.class).getType();
            return App.gson().fromJson(json, type);
        } catch (Exception e) {
            SpiderDebug.log(TAG, "danmaku asset parse failed: %s", e);
            return null;
        }
    }

    private static String readAsset(String path) {
        try (InputStream is = App.get().getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
