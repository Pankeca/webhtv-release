package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.CloudConfigSync;
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.DanmakuSourceSetting;
import com.fongmi.android.tv.setting.SharedConfigClient;
import com.fongmi.android.tv.ui.custom.CustomNestedScrollView;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 云端配置中心：五类共享配置的只读查看与勾选拉取。
 *
 * <p>普通用户只读；写入（增删改、恢复、审计）在管理入口完成。拉取前会把本地
 * 对应类别的旧数据写入 {@code config_shared_backup_*}，便于误覆盖后回退。
 */
public final class SharedConfigCenter {

    private static final String[] KEYS = SharedConfigClient.KEYS;
    private static final String[] LABELS = {"点播源", "直播源", "站点注入", "网页脚本", "弹幕来源"};
    private static final String BACKUP_PREFIX = "config_shared_backup_";

    private SharedConfigCenter() {
    }

    public static void show(FragmentActivity activity) {
        new Center(activity).show();
    }

    private static final class Center {

        private final FragmentActivity activity;
        private final Map<String, SharedConfigClient.Record> records = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>();
        private final Map<String, Boolean> selected = new LinkedHashMap<>();
        private final Map<String, TextView> statusViews = new LinkedHashMap<>();
        private final Map<String, TextView> previewViews = new LinkedHashMap<>();
        private AlertDialog dialog;

        Center(FragmentActivity activity) {
            this.activity = activity;
            // 点播/直播/弹幕默认勾选；站点与脚本会落到文件，默认需要用户显式确认
            selected.put(SharedConfigClient.KEY_VOD, true);
            selected.put(SharedConfigClient.KEY_LIVE, true);
            selected.put(SharedConfigClient.KEY_SITES, false);
            selected.put(SharedConfigClient.KEY_WEB_RULES, false);
            selected.put(SharedConfigClient.KEY_DANMAKU, true);
        }

        void show() {
            LinearLayoutCompat content = new LinearLayoutCompat(activity);
            content.setOrientation(LinearLayoutCompat.VERTICAL);
            content.setPadding(dp(20), dp(4), dp(20), dp(4));

            boolean admin = AccountStore.isAdminUnlocked();
            TextView note = text(admin
                    ? "勾选后拉取到本机；覆盖前会备份本地旧数据。已解锁管理员，可进入每类编辑。"
                    : "勾选后拉取到本机；覆盖前会备份本地旧数据。普通用户只读。", 12, "#6B7280");
            note.setPadding(0, 0, 0, dp(6));
            content.addView(note);

            for (int i = 0; i < KEYS.length; i++) content.addView(categoryBlock(KEYS[i], LABELS[i]));

            LinearLayoutCompat actions = new LinearLayoutCompat(activity);
            actions.setOrientation(LinearLayoutCompat.HORIZONTAL);
            actions.setPadding(0, dp(10), 0, 0);
            actions.addView(button("刷新", false, view -> loadAll()), weight());
            actions.addView(button("拉取所选", true, view -> pullSelected()), weight(true));
            content.addView(actions);

            CustomNestedScrollView scroll = new CustomNestedScrollView(activity);
            scroll.setFillViewport(false);
            scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setMaxHeight(SharedConfigUi.maxContentHeight(activity, false));

            dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle("云端配置中心")
                    .setView(scroll)
                    .setNegativeButton(R.string.dialog_close, null)
                    .show();
            // 四形态统一由 SharedConfigUi 算窗口宽度与滚动高度
            SharedConfigUi.applyWindow(dialog, activity);
            loadAll();
        }

        /** 一个类别：勾选行 + 版本状态 + 前几条预览。 */
        private View categoryBlock(String key, String label) {
            LinearLayoutCompat block = new LinearLayoutCompat(activity);
            block.setOrientation(LinearLayoutCompat.VERTICAL);
            block.setPadding(0, dp(8), 0, dp(8));

            CheckBox check = new CheckBox(activity);
            check.setText(label);
            check.setTextSize(14);
            check.setChecked(Boolean.TRUE.equals(selected.get(key)));
            check.setPadding(0, 0, 0, 0);
            // 48dp 点击区：窄屏/横屏也不容易点空
            check.setMinHeight(dp(48));
            check.setOnCheckedChangeListener((button, checked) -> selected.put(key, checked));
            block.addView(check);

            TextView status = text("读取中…", 11, "#8A8F98");
            status.setPadding(dp(28), 0, 0, 0);
            statusViews.put(key, status);
            block.addView(status);

            TextView preview = text("", 11, "#3C4043");
            preview.setPadding(dp(28), dp(2), 0, 0);
            preview.setMaxLines(4);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            previewViews.put(key, preview);
            block.addView(preview);

            // 管理员才显示编辑入口；普通用户严格只读
            if (AccountStore.isAdminUnlocked()) {
                View edit = button("编辑 " + label, false, view -> {
                    dialog.dismiss();
                    SharedConfigEditor.show(activity, key);
                });
                LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(28);
                params.topMargin = dp(6);
                block.addView(edit, params);
            }

            View line = new View(activity);
            line.setBackgroundColor(0xFFE6EAF0);
            LinearLayoutCompat.LayoutParams lineParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lineParams.topMargin = dp(8);
            block.addView(line, lineParams);
            return block;
        }

        /** 五类并行读取；管理员可看到软删除记录，普通用户只读可见项。 */
        private void loadAll() {
            errors.clear();
            boolean admin = AccountStore.isAdminUnlocked();
            for (int i = 0; i < KEYS.length; i++) {
                String key = KEYS[i];
                statusViews.get(key).setText("读取中…");
                previewViews.get(key).setText("");
                SharedConfigClient.RecordCallback callback = recordCallback(key);
                // 站点注入可能被拆成多片，必须合并后再展示
                if (SharedConfigClient.KEY_SITES.equals(key)) SharedConfigClient.loadMergedSites(admin, callback);
                else SharedConfigClient.load(key, admin, callback);
            }
        }

        private SharedConfigClient.RecordCallback recordCallback(String key) {
            return new SharedConfigClient.RecordCallback() {
                @Override
                public void success(SharedConfigClient.Record record) {
                    if (!alive()) return;
                    records.put(key, record);
                    errors.remove(key);
                    renderBlock(key);
                }

                @Override
                public void missing() {
                    if (!alive()) return;
                    records.remove(key);
                    errors.remove(key);
                    renderBlock(key);
                }

                @Override
                public void error(String message) {
                    if (!alive()) return;
                    records.remove(key);
                    errors.put(key, message);
                    renderBlock(key);
                }
            };
        }

        private void renderBlock(String key) {
            TextView status = statusViews.get(key);
            TextView preview = previewViews.get(key);
            if (status == null || preview == null) return;
            String error = errors.get(key);
            if (!TextUtils.isEmpty(error)) {
                status.setText("读取失败：" + error);
                preview.setText("");
                return;
            }
            SharedConfigClient.Record record = records.get(key);
            if (record == null) {
                // 云端空数据也要给明确空状态，不能只有一片空白
                status.setText("未发布 · 云端暂无该类别数据");
                preview.setText("");
                return;
            }
            if (!record.isValid()) {
                status.setText("结构不兼容（不会应用）");
                preview.setText("");
                return;
            }
            JsonArray items = record.items();
            status.setText("rev " + record.rev + " · " + items.size() + " 项"
                    + (TextUtils.isEmpty(record.updatedBy) ? "" : " · " + record.updatedBy)
                    + (record.deleted ? " · 已软删除（可恢复）" : ""));
            preview.setText(previewOf(record));
        }

        private String previewOf(SharedConfigClient.Record record) {
            List<String> names = new ArrayList<>();
            JsonArray items = record.items();
            for (int i = 0; i < items.size() && names.size() < 4; i++) {
                if (!items.get(i).isJsonObject()) continue;
                JsonObject item = items.get(i).getAsJsonObject();
                String name = optString(item, "name");
                if (TextUtils.isEmpty(name)) name = optString(item, "url");
                if (TextUtils.isEmpty(name)) name = optString(item, "key");
                if (!TextUtils.isEmpty(name)) names.add(name);
            }
            String text = names.isEmpty() ? "无条目" : String.join("、", names);
            if (items.size() > names.size()) text += " 等 " + items.size() + " 项";
            String active = record.activeUrl();
            if (!TextUtils.isEmpty(active)) text += "\n当前源：" + active;
            return text;
        }

        /** 逐个类别备份后应用；单类失败记录原因，其余类别继续。 */
        private void pullSelected() {
            List<String> keys = new ArrayList<>();
            for (String key : KEYS) if (Boolean.TRUE.equals(selected.get(key))) keys.add(key);
            if (keys.isEmpty()) {
                Notify.show("请先勾选要拉取的类别");
                return;
            }
            Notify.show("正在拉取…");
            if (dialog != null) dialog.dismiss();
            Task.execute(() -> {
                List<String> parts = new ArrayList<>();
                List<String> fails = new ArrayList<>();
                for (String key : keys) {
                    try {
                        if (records.get(key) == null || !records.get(key).isValid()) continue;
                        // 软删除记录仅管理员可见下展示，不能被普通拉取应用
                        if (records.get(key).deleted) continue;
                        backup(key);
                        String part = applyCategory(key, records.get(key));
                        if (!TextUtils.isEmpty(part)) parts.add(part);
                    } catch (Throwable e) {
                        fails.add(labelOf(key) + "：" + (e.getMessage() == null ? "应用失败" : e.getMessage()));
                    }
                }
                String message = parts.isEmpty() ? "云端暂无可用条目" : "已更新：" + String.join("、", parts);
                if (!fails.isEmpty()) message += "；失败：" + String.join("；", fails);
                final String result = message;
                App.post(() -> Notify.show(result));
            });
        }

        private String applyCategory(String key, SharedConfigClient.Record record) {
            switch (key) {
                case SharedConfigClient.KEY_VOD:
                case SharedConfigClient.KEY_LIVE:
                    return applyVodLive(record);
                case SharedConfigClient.KEY_SITES:
                    return applySites(record);
                case SharedConfigClient.KEY_WEB_RULES:
                    return applyWebRules(record);
                case SharedConfigClient.KEY_DANMAKU:
                    return applyDanmaku(record);
                default:
                    return "";
            }
        }

        private String applyVodLive(SharedConfigClient.Record record) {
            boolean vod = SharedConfigClient.KEY_VOD.equals(record.key);
            boolean live = SharedConfigClient.KEY_LIVE.equals(record.key);
            JsonObject data = new JsonObject();
            data.add(vod ? "vod" : "live", SharedConfigClient.toConfigArray(record));
            int[] result = CloudConfigSync.applyShared(data, vod, live, false, CloudSyncStore.conflictMode());
            int count = vod ? result[0] : result[1];
            return count <= 0 ? "" : (vod ? "点播源 " : "直播源 ") + count + " 项";
        }

        /** 站点注入只同步元数据：整体覆盖本机映射表，页面/图标仍由本地资源提供。 */
        private String applySites(SharedConfigClient.Record record) {
            JsonObject root = new JsonObject();
            root.addProperty("enabled", true);
            root.add("items", record.items().deepCopy());
            CustomCspSetting.Registry registry = CustomCspSetting.objectFrom(root.toString());
            CustomCspSetting.save(registry);
            return registry.getItems().isEmpty() ? "" : "站点 " + registry.getItems().size() + " 个";
        }

        /** 网页脚本追加去重写入本机；exclude 等本机尚不支持的字段跳过并提示。 */
        private String applyWebRules(SharedConfigClient.Record record) {
            List<WebHomeExtensionSourceStore.Entry> merged = new ArrayList<>(WebHomeExtensionSourceStore.list());
            Set<String> raws = new LinkedHashSet<>();
            for (WebHomeExtensionSourceStore.Entry entry : merged) raws.add(entry.getRaw());
            int added = 0;
            int skipped = 0;
            for (JsonObject item : itemsOf(record)) {
                String type = optString(item, "type");
                if (!TextUtils.isEmpty(type) && !"js".equalsIgnoreCase(type)) {
                    skipped++;
                    continue;
                }
                if (!TextUtils.isEmpty(optString(item, "exclude"))) {
                    skipped++;
                    continue;
                }
                String content = optString(item, "content");
                if (TextUtils.isEmpty(content)) {
                    skipped++;
                    continue;
                }
                String name = optString(item, "name");
                String runAt = optString(item, "runAt");
                if (!"start".equals(runAt) && !"end".equals(runAt)) runAt = "end";
                String match = optString(item, "match");
                if ("*".equals(match)) match = "";
                String siteKey = optString(item, "siteKey");
                String raw = WebHomeExtensionSourceStore.rawCode("", name, runAt, match, content,
                        WebHomeExtensionSourceStore.SOURCE_TYPE_CODE);
                if (!raws.add(raw)) continue;
                merged.add(WebHomeExtensionSourceStore.draft(name, raw, isEnabled(item), siteKey));
                added++;
            }
            if (added > 0) WebHomeExtensionSourceStore.saveRawJson(App.gson().toJson(merged));
            String part = added > 0 ? "脚本 " + added + " 条" : "";
            if (skipped > 0) part += (part.isEmpty() ? "" : "，") + "跳过 " + skipped + " 条（含本机暂不支持的字段）";
            return part;
        }

        private String applyDanmaku(SharedConfigClient.Record record) {
            List<JsonObject> items = orderedItems(record);
            List<DanmakuSource> sources = new ArrayList<>();
            for (JsonObject item : items) {
                String url = optString(item, "url");
                if (!DanmakuSource.create("", url).isValid()) continue;
                DanmakuSource source = DanmakuSource.create(optString(item, "name"), url);
                String id = optString(item, "id");
                if (!TextUtils.isEmpty(id)) source.setId(id);
                source.setEnabled(isEnabled(item));
                sources.add(source);
            }
            if (sources.isEmpty()) return "";
            DanmakuSourceSetting.saveAll(sources);
            return "弹幕来源 " + sources.size() + " 个";
        }

        /** activeUrl 置顶，其余按 order 升序；与 SharedConfigClient 的读取语义一致。 */
        private List<JsonObject> orderedItems(SharedConfigClient.Record record) {
            List<JsonObject> list = itemsOf(record);
            list.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
            String active = record.activeUrl();
            if (!TextUtils.isEmpty(active)) {
                for (int i = 0; i < list.size(); i++) {
                    if (active.equals(optString(list.get(i), "url"))) {
                        list.add(0, list.remove(i));
                        break;
                    }
                }
            }
            return list;
        }

        /** 覆盖前兜底：把本地旧数据放进排除同步的 config_ 前缀键里。 */
        private void backup(String key) {
            String text = "";
            switch (key) {
                case SharedConfigClient.KEY_VOD:
                    text = App.gson().toJson(Config.getAll(0));
                    break;
                case SharedConfigClient.KEY_LIVE:
                    text = App.gson().toJson(Config.getAll(1));
                    break;
                case SharedConfigClient.KEY_SITES:
                    text = Path.read(new File(CustomCspSetting.dir(), "registry.json"));
                    break;
                case SharedConfigClient.KEY_WEB_RULES:
                    text = App.gson().toJson(WebHomeExtensionSourceStore.list());
                    break;
                case SharedConfigClient.KEY_DANMAKU:
                    text = App.gson().toJson(DanmakuSourceSetting.getSources());
                    break;
            }
            JsonObject backup = new JsonObject();
            backup.addProperty("at", System.currentTimeMillis());
            backup.addProperty("text", text == null ? "" : text);
            Prefers.put(BACKUP_PREFIX + key, backup.toString());
        }

        private boolean alive() {
            return !activity.isFinishing() && dialog != null && dialog.isShowing();
        }

        private View button(String label, boolean primary, View.OnClickListener listener) {
            TextView view = text(label, 14, primary ? "#FFFFFF" : "#1A73E8");
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(12), dp(10), dp(12), dp(10));
            view.setMinHeight(dp(48));
            view.setMinWidth(dp(48));
            // 主按钮蓝底白字，次按钮浅灰底蓝字，与账号面板保持一致
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(android.graphics.Color.parseColor(primary ? "#1A73E8" : "#F1F4F9"));
            background.setCornerRadius(dp(12));
            view.setBackground(background);
            view.setClickable(true);
            view.setFocusable(true);
            view.setOnClickListener(listener);
            return view;
        }

        private LinearLayoutCompat.LayoutParams weight() {
            return weight(false);
        }

        private LinearLayoutCompat.LayoutParams weight(boolean leftMargin) {
            LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (leftMargin) params.leftMargin = dp(8);
            return params;
        }

        private TextView text(String value, int sp, String color) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(android.graphics.Color.parseColor(color));
            return view;
        }
    }

    private static List<JsonObject> itemsOf(SharedConfigClient.Record record) {
        List<JsonObject> items = new ArrayList<>();
        JsonArray array = record.items();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isJsonObject()) items.add(array.get(i).getAsJsonObject());
        }
        return items;
    }

    private static boolean isEnabled(JsonObject item) {
        try {
            JsonElement element = item.get("enabled");
            return element == null || element.isJsonNull() || element.getAsBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int orderOf(JsonObject item) {
        try {
            JsonElement element = item.get("order");
            return element == null || element.isJsonNull() ? Integer.MAX_VALUE : element.getAsInt();
        } catch (Throwable ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String optString(JsonObject object, String name) {
        try {
            JsonElement element = object == null ? null : object.get(name);
            return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String labelOf(String key) {
        for (int i = 0; i < SharedConfigClient.KEYS.length; i++) {
            if (SharedConfigClient.KEYS[i].equals(key)) return LABELS[i];
        }
        return key;
    }

    private static int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
