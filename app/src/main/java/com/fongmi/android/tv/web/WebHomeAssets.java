package com.fongmi.android.tv.web;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

/**
 * WebHome 内置脚本资产安装：把 assets/webhome/ 下的脚本落到 files/webhome/，
 * 供扩展的 {@code local://webhome/xxx.js} 引用（{@code WebHomeExtension.readCached}
 * 对 {@code local://} 走 {@link Path#files} 读取）。
 *
 * <p>为什么不在导入时把脚本全文内联进注册表：注册表会随站点数膨胀几十 KB，
 * 且 App 升级带了新脚本后已导入站点还在用旧副本。落成共享文件后，
 * 脚本随 App 更新自动生效，注册表只存每个站点的轻量 {@code __fmAutoConfig} 覆写。
 */
public final class WebHomeAssets {

    private static final String DIR = "webhome";
    /** 通用自适应扩展脚本（一键导入站点的默认注入）。 */
    public static final String UNIVERSAL = "universal-enhance.js";

    private WebHomeAssets() {
    }

    /** 启动与导入时调用。幂等：内容一致时不动文件，开销可忽略。 */
    public static void ensureInstalled() {
        install(UNIVERSAL);
    }

    private static void install(String name) {
        try {
            byte[] asset = readAsset(DIR + "/" + name);
            if (asset == null || asset.length == 0) {
                SpiderDebug.log("webhome-assets", "install skipped: asset empty name=%s", name);
                return;
            }
            File target = Path.files(DIR + "/" + name);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (target.exists() && target.length() == asset.length && Arrays.equals(readFile(target), asset)) return;
            Path.write(target, asset);
            SpiderDebug.log("webhome-assets", "installed name=%s bytes=%d", name, asset.length);
        } catch (Throwable e) {
            SpiderDebug.log("webhome-assets", "install failed name=%s error=%s", name, e.getMessage());
        }
    }

    private static byte[] readAsset(String path) {
        try (InputStream is = App.get().getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readFile(File file) {
        try (InputStream is = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) out.write(buffer, 0, len);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code local://} 引用串，给站点扩展的 js 字段用。 */
    public static String localUrl(String name) {
        return "local://" + DIR + "/" + name;
    }
}
