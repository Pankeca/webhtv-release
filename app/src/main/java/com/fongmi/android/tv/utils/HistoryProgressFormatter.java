package com.fongmi.android.tv.utils;

import java.util.Locale;
import java.util.regex.Pattern;

public final class HistoryProgressFormatter {

    /** 「第01集」「第 1 集」→ 捕获集号，统一成「第1集」。 */
    private static final Pattern EPISODE = Pattern.compile("第\\s*0*(\\d+)\\s*集");
    /** 「更新至第12集」在「已看」行里要退回成「第12集」，否则一行里出现两个「更新至」。 */
    private static final Pattern UPDATE_PREFIX = Pattern.compile("^(更新至|更新到)\\s*");

    private HistoryProgressFormatter() {
    }

    public static String format(long positionMs, long durationMs) {
        if (positionMs <= 0) return "";
        long displayMs = durationMs > 0 ? Math.min(positionMs, durationMs) : positionMs;
        long seconds = displayMs / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60);
    }

    /**
     * 把站点备注收敛成「第N集」这一种写法。
     *
     * <p>不同站点的备注五花八门：「第01集」「第 1 集」「更新至第12集」。
     * 追更和最近观看两张列表并排看的时候，一边「已看 第01集」一边「更新至 第 1 集」会显得很乱，
     * 所以统一成「第1集」；认不出集数的备注（「HD国语」「全13集」）原样保留，不硬改。
     */
    public static String episode(String remarks) {
        if (remarks == null) return "";
        String text = remarks.trim();
        if (text.isEmpty()) return "";
        text = EPISODE.matcher(text).replaceAll("第$1集");
        return UPDATE_PREFIX.matcher(text).replaceFirst("");
    }
}
