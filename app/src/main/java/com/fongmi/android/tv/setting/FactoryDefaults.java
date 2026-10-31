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
 * 出厂默认配置：弹幕源 + 站点注入。
 *
 * <p>为什么需要——debug 与 release 签名不一致，装发布包前必须先卸载，而卸载会清掉
 * SharedPreferences 和外部存储里的站点注入，用户手工配好的弹幕源与站点会一起消失，
 * 「换个包就白干」。这里把一份可用的出厂配置打进 assets，全新安装或清数据后自动播种回来。
 *
 * <p><b>为什么不用「只播种一次」的标记位：</b>第一版是在 SharedPreferences 里写
 * {@code factory_csp_seeded} 之类的一次性标记。实测踩坑——先装包启动一次（那时配置还在）
 * 就顺手把标记置真了，之后配置文件一旦丢失，标记仍为真，永远不再播种，等于出厂默认形同虚设。
 * 改成按「用户有没有配置」判断，天然幂等，也不需要维护标记：
 *
 * <ol>
 *   <li><b>弹幕源</b>：只要 {@link DanmakuSourceSetting#hasCustomSources()} 为假就播种。
 *       用户把来源删空后存的是 {@code "[]"}，非空字符串，仍然算「配过」，不会被重新塞回来。</li>
 *   <li><b>站点注入</b>：只要 {@code registry.json} 不存在就播种。用户在界面上删空站点时
 *       {@link CustomCspSetting#save} 仍会写出一个空列表文件，文件既然在，就不会被重新塞回来；
 *       而卸载、清数据、升级把文件搞没了，下次启动自动补回。</li>
 *   <li><b>失败不标记、下次重试</b>：站点注入要写外部存储，存储权限还没授予时会失败，
 *       因为没有标记位，下次启动自然会再试一次，直到真正落盘。</li>
 * </ol>
 */
public final class FactoryDefaults {

    private static final String TAG = "factory";
    private static final String ASSET_DANMAKU = "factory/danmaku_sources.json";
    private static final String ASSET_CSP = "factory/custom_csp.json";

    private FactoryDefaults() {
    }

    /** 启动时调用。幂等，可以每次启动都调。 */
    public static void applyIfNeeded() {
        seedDanmaku();
        seedCustomCsp();
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

    private static void seedCustomCsp() {
        if (CustomCspSetting.hasRegistry()) return;
        String json = readAsset(ASSET_CSP);
        if (TextUtils.isEmpty(json)) {
            SpiderDebug.log(TAG, "custom-csp seed skipped: asset empty");
            return;
        }
        try {
            CustomCspSetting.Registry registry = CustomCspSetting.parse(json);
            if (registry == null) return;
            CustomCspSetting.save(registry);
            SpiderDebug.log(TAG, "custom-csp seeded items=%d file=%s", CustomCspSetting.countItems(), CustomCspSetting.dir());
        } catch (Exception e) {
            // 存储权限未授予等情况下写不进去，不要写任何标记，下次启动继续重试
            SpiderDebug.log(TAG, "custom-csp seed failed: %s", e);
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
