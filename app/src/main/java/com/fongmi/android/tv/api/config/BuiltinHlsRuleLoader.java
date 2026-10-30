// 来源：alantang1977/tangtv（GPL-3.0）经逐行核对后移植；改动处均有注释标注。
// 设计文档见 docs/tangtv-integration-plan.md「阶段 2：HLS 去广」。
package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.bean.HlsAdRule;
import com.fongmi.android.tv.bean.HlsRulePackage;
import com.github.catvod.utils.Asset;

import java.util.List;

public final class BuiltinHlsRuleLoader {

    private static final String ASSET = "rules/hls_rules.json";
    private static volatile HlsRulePackage cached;

    private BuiltinHlsRuleLoader() {}

    public static HlsRulePackage getPackage() {
        HlsRulePackage value = cached;
        if (value != null) return value;
        synchronized (BuiltinHlsRuleLoader.class) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    public static List<HlsAdRule> getRules() {
        return getPackage().getRules();
    }

    private static HlsRulePackage load() {
        return HlsRulePackage.parse(Asset.read(ASSET));
    }
}
