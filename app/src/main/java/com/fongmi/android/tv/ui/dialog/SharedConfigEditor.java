package com.fongmi.android.tv.ui.dialog;

import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.SharedConfigClient;
import com.fongmi.android.tv.setting.SharedConfigWriter;
import com.fongmi.android.tv.ui.custom.CustomNestedScrollView;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 管理员共享配置编辑器：五类条目的增删改、启停、排序、软删除/恢复/彻底删除、
 * 审计查看与「用本机覆盖云端」。
 *
 * <p>保存一律带 rev；409 时弹冲突框，可选重新加载或用本地版本覆盖。
 * 入口只在 {@link AccountStore#isAdminUnlocked()} 为真时显示，普通用户不可写。
 */
public final class SharedConfigEditor {

    private SharedConfigEditor() {
    }

    public static void show(FragmentActivity activity, String key) {
        if (!AccountStore.isAdminUnlocked()) return;
        new Editor(activity, key).show();
    }

    /** 删除/恢复类操作要求填写原因，空则用默认文案。 */
    private interface ReasonCallback {

        void ok(String reason);
    }

    private static final class Editor {

        private final FragmentActivity activity;
        private final String key;
        private final String label;
        /** 站点注入走分片写，其他类别单 key 条件写。 */
        private final boolean siteMode;
        private final List<JsonObject> items = new ArrayList<>();
        private final TextView status;
        private final LinearLayoutCompat rows;
        private final TextView hint;
        private AlertDialog dialog;
        private String activeUrl = "";
        /** 最近一次服务端记录；用于覆盖云端前的备份与冲突展示。 */
        private SharedConfigClient.Record server;
        private long rev;

        Editor(FragmentActivity activity, String key) {
            this.activity = activity;
            this.key = key;
            this.label = labelOf(key);
            this.siteMode = SharedConfigClient.KEY_SITES.equals(key);
            this.status = text("读取中…", 11, "#6B7280");
            this.hint = text("", 11, "#8A8F98");
            this.rows = new LinearLayoutCompat(activity);
            rows.setOrientation(LinearLayoutCompat.VERTICAL);
        }

        void show() {
            LinearLayoutCompat content = new LinearLayoutCompat(activity);
            content.setOrientation(LinearLayoutCompat.VERTICAL);
            content.setPadding(dp(20), dp(6), dp(20), dp(4));

            TextView title = text(label + " · 云端编辑", 15, "#202124");
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            content.addView(title);

            status.setPadding(0, dp(2), 0, dp(2));
            content.addView(status);
            hint.setPadding(0, 0, 0, dp(6));
            content.addView(hint);

            content.addView(rows);

            content.addView(actionRow(textButton("刷新", view -> load()),
                    textButton("新增条目", view -> editItem(-1))));
            content.addView(actionRow(textButton("保存发布", view -> saveToServer(null), true),
                    textButton("用本机覆盖", view -> publishLocal())));
            content.addView(actionRow(textButton("审计", view -> showAudit()),
                    textButton("整类删除", view -> confirmDelete()),
                    textButton("恢复", view -> doRestore()),
                    textButton("彻底删除", view -> confirmPurge())));

            TextView note = text("保存会带 rev 条件写；冲突时会让选择重新加载或覆盖。条目删除保存后不可单独恢复；只有「整类删除」是软删除、可恢复。", 11, "#8A8F98");
            note.setPadding(0, dp(8), 0, 0);
            content.addView(note);

            CustomNestedScrollView scroll = new CustomNestedScrollView(activity);
            scroll.setFillViewport(false);
            scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setMaxHeight(SharedConfigUi.maxContentHeight(activity, false));

            dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle("共享配置编辑")
                    .setView(scroll)
                    .setNegativeButton(R.string.dialog_close, null)
                    .show();
            // 四形态宽度与滚动高度统一在这里算，横屏矮屏不被系统栏裁掉
            SharedConfigUi.applyWindow(dialog, activity);
            load();
        }

        /** 读取服务端记录；站点类合并全部分片。缺失时弹幕源按迁移策略预填本机数据。 */
        private void load() {
            status.setText("读取中…");
            SharedConfigClient.RecordCallback callback = new SharedConfigClient.RecordCallback() {
                @Override
                public void success(SharedConfigClient.Record record) {
                    if (!alive()) return;
                    apply(record);
                }

                @Override
                public void missing() {
                    if (!alive()) return;
                    server = null;
                    rev = 0;
                    items.clear();
                    activeUrl = "";
                    // 迁移只保留弹幕源：首次打开且云端未发布时，用本机来源预填，仍需保存才落库
                    if (SharedConfigClient.KEY_DANMAKU.equals(key)) {
                        items.addAll(SharedConfigWriter.localItems(key));
                        activeUrl = SharedConfigWriter.localActiveUrl(key);
                    }
                    hint.setText(items.isEmpty() ? "云端尚未发布，可直接新增条目或「用本机覆盖」。" : "已预填本机弹幕源，保存后才会发布。");
                    status.setText("未发布 · 本地草稿");
                    renderRows();
                }

                @Override
                public void error(String message) {
                    if (!alive()) return;
                    status.setText("读取失败：" + message);
                }
            };
            if (SharedConfigClient.KEY_SITES.equals(key)) SharedConfigClient.loadMergedSites(true, callback);
            else SharedConfigClient.load(key, true, callback);
        }

        private void apply(SharedConfigClient.Record record) {
            server = record;
            rev = record.rev;
            items.clear();
            activeUrl = record.activeUrl();
            if (!record.deleted) {
                for (JsonObject item : itemsOf(record)) items.add(item.deepCopy());
            }
            StringBuilder builder = new StringBuilder("rev ").append(record.rev);
            if (!TextUtils.isEmpty(record.updatedBy)) builder.append(" · ").append(record.updatedBy);
            if (record.updatedAt > 0) builder.append(" · ").append(time(record.updatedAt));
            if (record.deleted) builder.append(" · 已软删除");
            status.setText(builder.toString());
            hint.setText(record.deleted ? "该类别已软删除，可点「恢复」；保存会用当前内容重新发布。" : "");
            renderRows();
        }

        /** 条目列表：每行两排按钮，窄屏也能点到。 */
        private void renderRows() {
            rows.removeAllViews();
            if (items.isEmpty()) {
                TextView empty = text("暂无条目", 12, "#8A8F98");
                empty.setPadding(dp(2), dp(8), 0, dp(8));
                rows.addView(empty);
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                final int index = i;
                JsonObject item = items.get(i);
                LinearLayoutCompat block = new LinearLayoutCompat(activity);
                block.setOrientation(LinearLayoutCompat.VERTICAL);
                block.setPadding(0, dp(8), 0, dp(8));

                String name = displayName(item);
                boolean enabled = isEnabled(item);
                boolean current = SharedConfigWriter.supportsActive(key) && !TextUtils.isEmpty(activeUrl)
                        && activeUrl.equals(optString(item, "url"));
                TextView title = text((i + 1) + ". " + (TextUtils.isEmpty(name) ? "(未命名)" : name)
                        + (enabled ? "" : " · 已停用") + (current ? " · 当前源" : ""), 13, enabled ? "#202124" : "#9AA0A6");
                block.addView(title);

                TextView summary = text(summaryOf(item), 11, "#5F6368");
                summary.setMaxLines(2);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                block.addView(summary);

                // 站点注入契约没有 enabled 字段，不提供启停，避免写入未知字段
                block.addView(actionRow(
                        textButton("编辑", view -> editItem(index)),
                        siteMode ? null : textButton(enabled ? "停用" : "启用", view -> toggle(index)),
                        textButton("上移", view -> move(index, -1)),
                        textButton("下移", view -> move(index, 1))));
                block.addView(actionRow(
                        textButton("删除", view -> removeItem(index)),
                        SharedConfigWriter.supportsActive(key)
                                ? textButton("设为当前", view -> setActive(index)) : null));

                View line = new View(activity);
                line.setBackgroundColor(0xFFE6EAF0);
                block.addView(line, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                rows.addView(block);
            }
        }

        private void toggle(int index) {
            if (siteMode) return;
            JsonObject item = items.get(index);
            item.addProperty("enabled", !isEnabled(item));
            renderRows();
        }

        private void move(int index, int delta) {
            int to = index + delta;
            if (to < 0 || to >= items.size()) {
                Notify.show("已到边界");
                return;
            }
            items.add(to, items.remove(index));
            renderRows();
        }

        private void setActive(int index) {
            String url = optString(items.get(index), "url");
            if (TextUtils.isEmpty(url)) {
                Notify.show("该条目没有地址");
                return;
            }
            activeUrl = url;
            renderRows();
        }

        private void removeItem(int index) {
            new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle("删除条目")
                    .setMessage("从本地草稿移除「" + displayName(items.get(index)) + "」？保存发布后该条目无法单独恢复；只想临时下线请用「停用」。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        items.remove(index);
                        renderRows();
                    }).show();
        }

        private void editItem(int index) {
            boolean isNew = index < 0;
            JsonObject source = isNew ? new JsonObject() : items.get(index).deepCopy();
            LinearLayoutCompat form = new LinearLayoutCompat(activity);
            form.setOrientation(LinearLayoutCompat.VERTICAL);
            form.setPadding(dp(20), dp(6), dp(20), dp(6));

            List<EditText> fields = new ArrayList<>();
            switch (key) {
                case SharedConfigClient.KEY_VOD:
                case SharedConfigClient.KEY_LIVE:
                    fields.add(field(form, "名称", optString(source, "name"), false, 0));
                    fields.add(field(form, "地址（必填，http/https）", optString(source, "url"), false, 0));
                    fields.add(field(form, "图标 logo（可选）", optString(source, "logo"), false, 0));
                    fields.add(field(form, "主页 home（可选）", optString(source, "home"), false, 0));
                    fields.add(field(form, "解析 parse（可选）", optString(source, "parse"), false, 0));
                    fields.add(field(form, "排序 order（数字）", orderText(source), false, InputType.TYPE_CLASS_NUMBER));
                    break;
                case SharedConfigClient.KEY_SITES:
                    fields.add(field(form, "站点 key（必填）", optString(source, "key"), false, 0));
                    fields.add(field(form, "名称", optString(source, "name"), false, 0));
                    fields.add(field(form, "类型 type（数字，默认 3）", typeText(source), false, InputType.TYPE_CLASS_NUMBER));
                    fields.add(field(form, "api（必填）", optString(source, "api"), false, 0));
                    fields.add(field(form, "ext（JSON 对象，可空）", extText(source), true, 0));
                    break;
                case SharedConfigClient.KEY_WEB_RULES:
                    fields.add(field(form, "名称", optString(source, "name"), false, 0));
                    fields.add(field(form, "匹配 match（可空=全部）", matchText(source), true, 0));
                    fields.add(field(form, "运行时机 runAt（start/end）", runAtOf(source), false, 0));
                    fields.add(field(form, "归属站点 siteKey（可选）", optString(source, "siteKey"), false, 0));
                    fields.add(field(form, "排除 exclude（可选）", optString(source, "exclude"), false, 0));
                    fields.add(field(form, "脚本 content（必填）", optString(source, "content"), true, 0));
                    break;
                case SharedConfigClient.KEY_DANMAKU:
                    fields.add(field(form, "名称", optString(source, "name"), false, 0));
                    fields.add(field(form, "地址（必填，http/https）", optString(source, "url"), false, 0));
                    break;
                default:
                    return;
            }

            CustomNestedScrollView scroll = new CustomNestedScrollView(activity);
            scroll.addView(form, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setMaxHeight(SharedConfigUi.maxContentHeight(activity, true));

            AlertDialog edit = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(isNew ? "新增条目" : "编辑条目")
                    .setView(scroll)
                    .setNegativeButton("取消", null)
                    .show();
            // 表单含输入框：清 ALT_FOCUSABLE_IM + adjustResize，避免键盘遮挡底部按钮
            SharedConfigUi.applyFormWindow(edit, activity);

            LinearLayoutCompat footer = new LinearLayoutCompat(activity);
            footer.setOrientation(LinearLayoutCompat.HORIZONTAL);
            footer.setPadding(dp(20), dp(6), dp(20), dp(6));
            TextView save = textButton("保存到草稿", view -> {
                JsonObject built = buildItem(source, fields);
                if (built == null) return;
                if (isNew) items.add(built);
                else items.set(index, built);
                edit.dismiss();
                renderRows();
            }, true);
            footer.addView(save, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // 把按钮追加到对话框内容区，避免 setPositiveButton 校验失败就关窗
            form.addView(footer);
        }

        /** 字段校验通过后构建条目；仅保留契约字段，避免脏字段混入。 */
        private JsonObject buildItem(JsonObject source, List<EditText> fields) {
            JsonObject item = new JsonObject();
            switch (key) {
                case SharedConfigClient.KEY_VOD:
                case SharedConfigClient.KEY_LIVE: {
                    String url = value(fields.get(1));
                    if (!isHttp(url)) {
                        Notify.show("请填写 http/https 地址");
                        return null;
                    }
                    item.addProperty("name", value(fields.get(0)));
                    item.addProperty("url", url);
                    String id = optString(source, "id");
                    if (!TextUtils.isEmpty(id)) item.addProperty("id", id);
                    copyOptional(item, "logo", value(fields.get(2)));
                    copyOptional(item, "home", value(fields.get(3)));
                    copyOptional(item, "parse", value(fields.get(4)));
                    item.addProperty("enabled", isEnabled(source));
                    item.addProperty("order", intOf(value(fields.get(5)), items.size()));
                    break;
                }
                case SharedConfigClient.KEY_SITES: {
                    String siteKey = value(fields.get(0));
                    String api = value(fields.get(3));
                    if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(api)) {
                        Notify.show("站点 key 与 api 必填");
                        return null;
                    }
                    String ext = value(fields.get(4));
                    JsonObject extObject = null;
                    if (!TextUtils.isEmpty(ext)) {
                        try {
                            JsonElement element = JsonParser.parseString(ext);
                            if (element.isJsonObject()) extObject = element.getAsJsonObject();
                        } catch (Throwable ignored) {
                            extObject = null;
                        }
                        if (extObject == null) {
                            Notify.show("ext 需为 JSON 对象（如 {\"ua\":\"okhttp\"}）");
                            return null;
                        }
                    }
                    item.addProperty("key", siteKey);
                    item.addProperty("name", value(fields.get(1)));
                    item.addProperty("type", intOf(value(fields.get(2)), 3));
                    item.addProperty("api", api);
                    if (extObject != null) item.add("ext", extObject);
                    break;
                }
                case SharedConfigClient.KEY_WEB_RULES: {
                    String content = value(fields.get(5));
                    if (TextUtils.isEmpty(content)) {
                        Notify.show("脚本内容不能为空");
                        return null;
                    }
                    String runAt = value(fields.get(2));
                    if (!"start".equals(runAt) && !"end".equals(runAt)) runAt = "end";
                    String match = value(fields.get(1));
                    if (TextUtils.isEmpty(match)) match = "*";
                    item.addProperty("name", value(fields.get(0)));
                    item.addProperty("type", "js");
                    item.addProperty("content", content);
                    item.addProperty("match", match);
                    item.addProperty("exclude", value(fields.get(4)));
                    item.addProperty("runAt", runAt);
                    item.addProperty("enabled", isEnabled(source));
                    item.addProperty("siteKey", value(fields.get(3)));
                    break;
                }
                case SharedConfigClient.KEY_DANMAKU: {
                    String url = value(fields.get(1));
                    if (!isHttp(url)) {
                        Notify.show("请填写 http/https 地址");
                        return null;
                    }
                    item.addProperty("name", value(fields.get(0)));
                    item.addProperty("url", url);
                    item.addProperty("enabled", isEnabled(source));
                    break;
                }
                default:
                    return null;
            }
            return item;
        }

        /** 保存到云端：点播/直播按数组顺序写 order，站点类自动分片。 */
        private void saveToServer(SharedConfigWriter.WriteCallback retry) {
            if (siteMode) {
                SharedConfigWriter.saveSites(items, "编辑站点注入", retry != null ? retry : defaultWriteCallback());
                return;
            }
            normalizeOrder();
            SharedConfigWriter.save(key, items, activeUrl, rev, "编辑共享配置",
                    retry != null ? retry : defaultWriteCallback());
        }

        private SharedConfigWriter.WriteCallback defaultWriteCallback() {
            return new SharedConfigWriter.WriteCallback() {
                @Override
                public void success() {
                    if (!alive()) return;
                    Notify.show("已发布");
                    load();
                }

                @Override
                public void conflict(String conflictKey, SharedConfigClient.Record current) {
                    if (!alive()) return;
                    showConflict(conflictKey, current, () -> saveToServer(retryWrite()));
                }

                @Override
                public void error(String message) {
                    if (!alive()) return;
                    Notify.show("保存失败：" + message);
                }
            };
        }

        /** 冲突后「以我的版本覆盖」：单 key 用服务端新 rev 重试，站点类重新枚举分片。 */
        private SharedConfigWriter.WriteCallback retryWrite() {
            return new SharedConfigWriter.WriteCallback() {
                @Override
                public void success() {
                    if (!alive()) return;
                    Notify.show("已覆盖发布");
                    load();
                }

                @Override
                public void conflict(String conflictKey, SharedConfigClient.Record current) {
                    if (!alive()) return;
                    showConflict(conflictKey, current, () -> saveToServer(retryWrite()));
                }

                @Override
                public void error(String message) {
                    if (!alive()) return;
                    Notify.show("覆盖失败：" + message);
                }
            };
        }

        /** 409 冲突框：展示最后修改人/时间/摘要，三选一。 */
        private void showConflict(String conflictKey, SharedConfigClient.Record current, Runnable force) {
            String who = TextUtils.isEmpty(current.updatedBy) ? "未知用户" : current.updatedBy;
            String when = current.updatedAt > 0 ? time(current.updatedAt) : "未知时间";
            String preview = previewOf(current);
            String message = "服务端已被 " + who + " 于 " + when + " 修改"
                    + (conflictKey.equals(key) ? "" : "（分片 " + conflictKey + "）")
                    + "\nrev " + current.rev + "\n" + (preview.isEmpty() ? "" : "当前内容：" + preview);
            new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle("版本冲突")
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setNeutralButton("重新加载", (d, w) -> load())
                    .setPositiveButton("以我的版本覆盖", (d, w) -> {
                        // 单 key 冲突：用服务端最新 rev 作为新基准；站点分片在重试时自行重取
                        if (!siteMode && current.rev > 0) {
                            rev = current.rev;
                            server = current;
                        }
                        force.run();
                    }).show();
        }

        private void publishLocal() {
            confirm("用本机覆盖云端", "会用本机当前数据整体覆盖云端「" + label + "」，覆盖前备份云端内容到本机。确定继续？", () -> {
                Task.execute(() -> {
                    List<JsonObject> local = SharedConfigWriter.localItems(key);
                    String active = SharedConfigWriter.localActiveUrl(key);
                    App.post(() -> {
                        if (!alive()) return;
                        SharedConfigWriter.backupCloud(key, server);
                        items.clear();
                        items.addAll(local);
                        activeUrl = active;
                        renderRows();
                        saveToServer(defaultWriteCallback());
                    });
                });
            });
        }

        private void confirmDelete() {
            promptReason("整类软删除", "删除后普通用户拉取不到，可从「恢复」找回。", "删除配置项", reason -> {
                SharedConfigWriter.WriteCallback callback = new SharedConfigWriter.WriteCallback() {
                    @Override
                    public void success() {
                        Notify.show("已软删除");
                        load();
                    }

                    @Override
                    public void conflict(String conflictKey, SharedConfigClient.Record current) {
                        showConflict(conflictKey, current, this::retryDelete);
                    }

                    @Override
                    public void error(String message) {
                        Notify.show("删除失败：" + message);
                    }

                    private void retryDelete() {
                        if (siteMode) {
                            SharedConfigWriter.deleteSites(reason, this);
                        } else {
                            // 冲突框已把 rev 换成服务端最新值，直接重试
                            SharedConfigWriter.deleteSoft(key, rev, reason, this);
                        }
                    }
                };
                if (siteMode) SharedConfigWriter.deleteSites(reason, callback);
                else SharedConfigWriter.deleteSoft(key, rev, reason, callback);
            });
        }

        private void doRestore() {
            SharedConfigWriter.WriteCallback callback = new SharedConfigWriter.WriteCallback() {
                @Override
                public void success() {
                    Notify.show("已恢复");
                    load();
                }

                @Override
                public void conflict(String conflictKey, SharedConfigClient.Record current) {
                    showConflict(conflictKey, current, () -> doRestore());
                }

                @Override
                public void error(String message) {
                    Notify.show("恢复失败：" + message);
                }
            };
            if (siteMode) SharedConfigWriter.restoreSites("恢复配置项", callback);
            else SharedConfigWriter.restore(key, rev, "恢复配置项", callback);
        }

        private void confirmPurge() {
            promptReason("彻底删除", "不可恢复，仅用于清理误建的记录。确定继续？", "彻底删除配置项", reason -> {
                SharedConfigWriter.WriteCallback callback = new SharedConfigWriter.WriteCallback() {
                    @Override
                    public void success() {
                        Notify.show("已彻底删除");
                        load();
                    }

                    @Override
                    public void conflict(String conflictKey, SharedConfigClient.Record current) {
                        showConflict(conflictKey, current, () -> confirmPurge());
                    }

                    @Override
                    public void error(String message) {
                        Notify.show("彻底删除失败：" + message);
                    }
                };
                if (siteMode) SharedConfigWriter.purgeSites(reason, callback);
                else SharedConfigWriter.purge(key, rev, reason, callback);
            });
        }

        /** 审计日志：只存 hash 与操作摘要，不含正文。 */
        private void showAudit() {
            AuthApi.publicAudit(key, 50, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    if (!alive()) return;
                    List<String> lines = new ArrayList<>();
                    JsonArray array = response != null && response.has("items") && response.get("items").isJsonArray()
                            ? response.getAsJsonArray("items") : new JsonArray();
                    for (int i = 0; i < array.size(); i++) {
                        if (!array.get(i).isJsonObject()) continue;
                        JsonObject item = array.get(i).getAsJsonObject();
                        String action = optString(item, "action");
                        String who = optString(item, "updatedBy");
                        String reason = optString(item, "reason");
                        long at = longOf(item, "at");
                        lines.add(time(at) + " · " + action + " · rev " + longOf(item, "rev")
                                + (TextUtils.isEmpty(who) ? "" : " · " + who)
                                + (TextUtils.isEmpty(reason) ? "" : "\n    " + reason));
                    }
                    TextView body = text(lines.isEmpty() ? "暂无审计记录" : String.join("\n", lines), 12, "#3C4043");
                    body.setTextIsSelectable(true);
                    ScrollView scroll = new ScrollView(activity);
                    scroll.setPadding(dp(20), dp(8), dp(20), dp(8));
                    scroll.addView(body);
                    AlertDialog audit = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                            .setTitle(label + " · 审计（最近 " + lines.size() + " 条）")
                            .setView(scroll)
                            .setNegativeButton(R.string.dialog_close, null)
                            .show();
                    SharedConfigUi.applyWindow(audit, activity);
                }

                @Override
                public void error(String message) {
                    Notify.show("审计读取失败：" + message);
                }
            });
        }

        /** 点播/直播保存前把 order 归一为数组顺序。 */
        private void normalizeOrder() {
            if (!SharedConfigClient.KEY_VOD.equals(key) && !SharedConfigClient.KEY_LIVE.equals(key)) return;
            for (int i = 0; i < items.size(); i++) items.get(i).addProperty("order", i);
        }

        private void promptReason(String title, String message, String def, ReasonCallback callback) {
            EditText input = new EditText(activity);
            input.setHint("原因（可空）");
            input.setText("");
            input.setSingleLine(true);
            LinearLayoutCompat box = new LinearLayoutCompat(activity);
            box.setOrientation(LinearLayoutCompat.VERTICAL);
            box.setPadding(dp(20), dp(6), dp(20), dp(6));
            TextView note = text(message, 12, "#5F6368");
            note.setPadding(0, 0, 0, dp(6));
            box.addView(note);
            box.addView(input);
            AlertDialog reason = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(title)
                    .setView(box)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (d, w) -> {
                        String reason1 = input.getText().toString().trim();
                        callback.ok(TextUtils.isEmpty(reason1) ? def : reason1);
                    }).show();
            // 原因输入框同样要避开键盘
            SharedConfigUi.applyFormWindow(reason, activity);
        }

        private void confirm(String title, String message, Runnable ok) {
            new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (d, w) -> ok.run()).show();
        }

        private EditText field(LinearLayoutCompat form, String label, String value, boolean multiline, int inputType) {
            TextView caption = text(label, 12, "#5F6368");
            caption.setPadding(0, dp(8), 0, dp(2));
            form.addView(caption);
            EditText input = new EditText(activity);
            input.setText(value == null ? "" : value);
            input.setTextSize(13);
            if (multiline) {
                input.setMinLines(3);
                input.setMaxLines(8);
                input.setGravity(Gravity.TOP | Gravity.START);
            } else {
                input.setSingleLine(true);
            }
            if (inputType != 0) input.setInputType(inputType);
            form.addView(input, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return input;
        }

        private LinearLayoutCompat actionRow(View... views) {
            LinearLayoutCompat row = new LinearLayoutCompat(activity);
            row.setOrientation(LinearLayoutCompat.HORIZONTAL);
            row.setPadding(0, dp(2), 0, dp(2));
            for (View view : views) {
                if (view == null) continue;
                LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (row.getChildCount() > 0) params.leftMargin = dp(6);
                row.addView(view, params);
            }
            return row;
        }

        private TextView textButton(String label, View.OnClickListener listener) {
            return textButton(label, listener, false);
        }

        private TextView textButton(String label, View.OnClickListener listener, boolean primary) {
            TextView view = text(label, 12, primary ? "#FFFFFF" : "#1A73E8");
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(6), dp(9), dp(6), dp(9));
            // 48dp 点击区，手机窄屏横屏也能准确点中
            view.setMinHeight(dp(48));
            view.setMinWidth(dp(48));
            GradientDrawable background = new GradientDrawable();
            background.setColor(android.graphics.Color.parseColor(primary ? "#1A73E8" : "#F1F4F9"));
            background.setCornerRadius(dp(10));
            view.setBackground(background);
            view.setClickable(true);
            view.setFocusable(true);
            view.setOnClickListener(listener);
            return view;
        }

        private TextView text(String value, int sp, String color) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(android.graphics.Color.parseColor(color));
            return view;
        }

        private boolean alive() {
            return !activity.isFinishing() && dialog != null && dialog.isShowing();
        }

        private int dp(int value) {
            return ResUtil.dp2px(value);
        }
    }

    // ---------- 纯函数工具（条目字段读取/展示） ----------

    private static List<JsonObject> itemsOf(SharedConfigClient.Record record) {
        List<JsonObject> items = new ArrayList<>();
        JsonArray array = record.items();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isJsonObject()) items.add(array.get(i).getAsJsonObject());
        }
        return items;
    }

    private static String displayName(JsonObject item) {
        String name = optString(item, "name");
        if (TextUtils.isEmpty(name)) name = optString(item, "key");
        if (TextUtils.isEmpty(name)) name = optString(item, "url");
        return name;
    }

    private static String summaryOf(JsonObject item) {
        String url = optString(item, "url");
        if (!TextUtils.isEmpty(url)) return url;
        String api = optString(item, "api");
        if (!TextUtils.isEmpty(api)) return optString(item, "key") + " · " + api;
        String runAt = optString(item, "runAt");
        return "match " + (optString(item, "match").isEmpty() ? "*" : optString(item, "match"))
                + " · " + (TextUtils.isEmpty(runAt) ? "end" : runAt);
    }

    private static String previewOf(SharedConfigClient.Record record) {
        List<String> names = new ArrayList<>();
        JsonArray array = record.items();
        for (int i = 0; i < array.size() && names.size() < 4; i++) {
            if (!array.get(i).isJsonObject()) continue;
            String name = displayName(array.get(i).getAsJsonObject());
            if (!TextUtils.isEmpty(name)) names.add(name);
        }
        return names.isEmpty() ? record.items().size() + " 项" : String.join("、", names) + " 等 " + array.size() + " 项";
    }

    private static String labelOf(String key) {
        switch (key) {
            case SharedConfigClient.KEY_VOD:
                return "点播源";
            case SharedConfigClient.KEY_LIVE:
                return "直播源";
            case SharedConfigClient.KEY_SITES:
                return "站点注入";
            case SharedConfigClient.KEY_WEB_RULES:
                return "网页脚本";
            case SharedConfigClient.KEY_DANMAKU:
                return "弹幕来源";
            default:
                return key;
        }
    }

    private static String orderText(JsonObject item) {
        JsonElement element = item.get("order");
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String typeText(JsonObject item) {
        JsonElement element = item.get("type");
        return element == null || element.isJsonNull() ? "3" : element.getAsString();
    }

    private static String runAtOf(JsonObject item) {
        String value = optString(item, "runAt");
        return TextUtils.isEmpty(value) ? "end" : value;
    }

    private static String matchText(JsonObject item) {
        String value = optString(item, "match");
        return "*".equals(value) ? "" : value;
    }

    private static String extText(JsonObject item) {
        JsonElement element = item.get("ext");
        if (element == null || element.isJsonNull()) return "";
        return element.isJsonObject() ? element.toString() : "";
    }

    private static boolean isEnabled(JsonObject item) {
        try {
            JsonElement element = item.get("enabled");
            return element == null || element.isJsonNull() || element.getAsBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String value(EditText input) {
        return input == null ? "" : input.getText().toString().trim();
    }

    private static void copyOptional(JsonObject target, String name, String value) {
        if (!TextUtils.isEmpty(value)) target.addProperty(name, value);
    }

    private static int intOf(String text, int fallback) {
        try {
            return TextUtils.isEmpty(text) ? fallback : Integer.parseInt(text.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long longOf(JsonObject object, String name) {
        try {
            JsonElement element = object.get(name);
            return element == null || element.isJsonNull() ? 0 : element.getAsLong();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String optString(JsonObject object, String name) {
        try {
            JsonElement element = object == null ? null : object.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
            return element.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isHttp(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String time(long value) {
        if (value <= 0) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(value));
    }
}
