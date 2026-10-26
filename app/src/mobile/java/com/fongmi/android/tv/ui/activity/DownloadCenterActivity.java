package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.databinding.ActivityDownloadCenterBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 下载中心：对标可可影视的全屏下载页。
 * 两个页签（下载中 / 已下载），按剧分组，支持长按多选 + 全选批量删除、全部开始/暂停。
 */
public class DownloadCenterActivity extends BaseActivity implements DownloadTask.Listener {

    private static final int COLOR_DONE = 0xFF188038;
    private static final int COLOR_DONE_BG = 0xFFE6F4EA;
    private static final int COLOR_ERROR = 0xFFB3261E;
    private static final int COLOR_ERROR_BG = 0xFFFCE8E6;
    private static final int CHIP_COLUMNS = 4;

    private ActivityDownloadCenterBinding mBinding;
    private final Set<String> selected = new HashSet<>();
    private final Map<String, Boolean> expanded = new LinkedHashMap<>();
    private final Map<String, TextView> chipLabels = new LinkedHashMap<>();
    private final Map<String, LinearLayoutCompat> chipViews = new LinkedHashMap<>();
    private final Map<String, TextView> groupSummaries = new LinkedHashMap<>();
    private String renderSignature = "";
    private long storageAt;
    private int tabIndex;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadCenterActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadCenterBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        DownloadManager.get().reconcile();
        DownloadManager.get().resumeNow();
        initTabs();
        render();
    }

    @Override
    protected void initEvent() {
        mBinding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tabIndex = tab.getPosition();
                selected.clear();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        mBinding.selectAll.setOnClickListener(view -> {
            selected.clear();
            if (mBinding.selectAll.isChecked()) for (DownloadTask task : visibleTasks()) selected.add(task.getId());
            render();
        });
        mBinding.expandAll.setOnClickListener(view -> toggleAllGroups());
        mBinding.startAll.setOnClickListener(view -> {
            List<DownloadTask> targets = new ArrayList<>();
            for (DownloadTask task : visibleTasks()) if (task.getState() != DownloadTask.State.DONE && !task.isBusy()) targets.add(task);
            if (targets.isEmpty()) Notify.show(R.string.download_nothing_to_start);
            else DownloadManager.get().resume(targets);
        });
        mBinding.pauseAll.setOnClickListener(view -> {
            List<DownloadTask> targets = new ArrayList<>();
            for (DownloadTask task : visibleTasks()) if (task.isBusy()) targets.add(task);
            if (targets.isEmpty()) Notify.show(R.string.download_nothing_to_pause);
            else DownloadManager.get().pause(targets);
        });
        mBinding.delete.setOnClickListener(view -> onDelete());
        DownloadManager.get().addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadManager.get().reconcile();
        render();
    }

    @Override
    protected void onDestroy() {
        DownloadManager.get().removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onChange(List<DownloadTask> tasks) {
        if (isFinishing() || isDestroyed()) return;
        App.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            // 只有任务集合或状态变化时才重建视图，进度刷新原地改文字，避免闪烁和滚动跳动
            if (renderSignature.equals(signature())) refreshLabels();
            else render();
        });
    }

    private void initTabs() {
        mBinding.tabs.addTab(mBinding.tabs.newTab().setText(R.string.download_tab_active));
        mBinding.tabs.addTab(mBinding.tabs.newTab().setText(R.string.download_tab_done));
        mBinding.tabs.selectTab(mBinding.tabs.getTabAt(0));
    }

    private List<DownloadTask> visibleTasks() {
        List<DownloadTask> result = new ArrayList<>();
        for (DownloadTask task : DownloadManager.get().tasks()) {
            boolean done = task.getState() == DownloadTask.State.DONE;
            if (tabIndex == 1 ? done : !done) result.add(task);
        }
        return result;
    }

    private void render() {
        if (mBinding == null || isFinishing() || isDestroyed()) return;
        List<DownloadTask> visible = visibleTasks();
        chipLabels.clear();
        chipViews.clear();
        groupSummaries.clear();
        mBinding.empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.scroll.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.content.removeAllViews();

        // 剧集被删除后其选中态也应失效
        Set<String> alive = new HashSet<>();
        for (DownloadTask task : visible) alive.add(task.getId());
        selected.retainAll(alive);

        Map<String, List<DownloadTask>> groups = groupsOf(visible);
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) mBinding.content.addView(groupCard(entry.getKey(), entry.getValue()));

        mBinding.selectAll.setChecked(!visible.isEmpty() && selected.size() >= visible.size());
        mBinding.expandAll.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        boolean allExpanded = !groups.isEmpty();
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) if (!isExpanded(entry.getKey(), entry.getValue().size())) allExpanded = false;
        mBinding.expandAll.setText(allExpanded ? R.string.download_collapse_all : R.string.download_expand_all);
        mBinding.startAll.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        mBinding.pauseAll.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        mBinding.delete.setVisibility(View.VISIBLE);
        updateTabTitles();
        renderSignature = signature();
        updateStorage();
    }

    private Map<String, List<DownloadTask>> groupsOf(List<DownloadTask> tasks) {
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask task : tasks) groups.computeIfAbsent(task.getVodName(), key -> new ArrayList<>()).add(task);
        return groups;
    }

    /** 视图结构签名：任务 id、状态和展开状态。仅进度变化时保持相同。 */
    private String signature() {
        StringBuilder builder = new StringBuilder();
        for (DownloadTask task : visibleTasks()) builder.append(task.getId()).append(':').append(task.getState()).append(';');
        for (Map.Entry<String, Boolean> entry : expanded.entrySet()) builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        return builder.toString();
    }

    /** 进度/速度类刷新：只更新已有控件，不重建视图。 */
    private void refreshLabels() {
        for (DownloadTask task : visibleTasks()) {
            TextView label = chipLabels.get(task.getId());
            LinearLayoutCompat chip = chipViews.get(task.getId());
            if (label != null) {
                label.setText(chipLabel(task));
                label.setTextColor(chipTextColor(task));
                label.setTypeface(Typeface.DEFAULT, task.getState() == DownloadTask.State.DONE ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (chip != null) chip.setBackground(chipBackground(task, selected.contains(task.getId())));
        }
        for (Map.Entry<String, List<DownloadTask>> entry : groupsOf(visibleTasks()).entrySet()) {
            TextView summary = groupSummaries.get(entry.getKey());
            if (summary == null) continue;
            summary.setText(groupSummaryText(entry.getValue()));
        }
        updateTabTitles();
    }

    /** 分组摘要：集数 + 已下载数，下载中时追加实时速度与剩余时间。 */
    private String groupSummaryText(List<DownloadTask> items) {
        int done = 0;
        long speed = 0;
        long eta = 0;
        for (DownloadTask task : items) {
            if (task.getState() == DownloadTask.State.DONE) done++;
            if (task.getState() == DownloadTask.State.RUNNING) {
                speed += task.getSpeed();
                eta = Math.max(eta, task.getEtaMillis());
            }
        }
        String text = getString(R.string.download_group_summary, items.size(), done);
        if (speed > 0) {
            text += " · " + DownloadTask.formatSpeed(speed);
            if (eta > 0) text += " · " + getString(R.string.download_eta, DownloadTask.formatEta(eta));
        }
        return text;
    }

    private void updateStorage() {
        long now = System.currentTimeMillis();
        if (now - storageAt < 5000) return;
        storageAt = now;
        File root = DownloadTask.rootDir();
        Task.submit(() -> {
            long used = sizeOf(root);
            long free = root.getUsableSpace();
            String text = getString(R.string.download_storage_usage, DownloadTask.formatSize(used), DownloadTask.formatSize(free));
            App.post(() -> {
                if (mBinding != null && !isFinishing() && !isDestroyed()) mBinding.toolbar.setSubtitle(text);
            });
        });
    }

    private long sizeOf(File file) {
        if (file == null) return 0;
        if (file.isFile()) return file.length();
        File[] files = file.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File child : files) total += sizeOf(child);
        return total;
    }

    private void updateTabTitles() {
        int active = 0;
        int done = 0;
        for (DownloadTask task : DownloadManager.get().tasks()) {
            if (task.getState() == DownloadTask.State.DONE) done++;
            else active++;
        }
        mBinding.tabs.getTabAt(0).setText(countText(R.string.download_tab_active, active));
        mBinding.tabs.getTabAt(1).setText(countText(R.string.download_tab_done, done));
    }

    private CharSequence countText(int resId, int count) {
        return count > 0 ? getString(resId) + " " + count : getString(resId);
    }

    private View groupCard(String vodName, List<DownloadTask> items) {
        DownloadTask first = items.get(0);
        LinearLayoutCompat card = new LinearLayoutCompat(this);
        card.setOrientation(LinearLayoutCompat.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(cardBackground());
        LinearLayoutCompat.LayoutParams cardParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayoutCompat header = new LinearLayoutCompat(this);
        header.setOrientation(LinearLayoutCompat.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImgUtil.load(vodName, first.getVodPic(), cover, true);
        header.addView(cover, new LinearLayoutCompat.LayoutParams(dp(56), dp(78)));

        LinearLayoutCompat info = new LinearLayoutCompat(this);
        info.setOrientation(LinearLayoutCompat.VERTICAL);
        info.setPadding(dp(10), 0, dp(8), 0);
        info.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = text(vodName, 16, colorOnSurface(), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        int done = 0;
        for (DownloadTask task : items) if (task.getState() == DownloadTask.State.DONE) done++;
        TextView summary = text(groupSummaryText(items), 13, colorOnSurfaceVariant(), false);
        summary.setPadding(0, dp(2), 0, 0);
        info.addView(summary);
        groupSummaries.put(vodName, summary);
        header.addView(info);

        // 每部剧的全选框
        boolean groupAll = true;
        for (DownloadTask task : items) if (!selected.contains(task.getId())) groupAll = false;
        CheckBox groupSelect = new CheckBox(this);
        groupSelect.setText(R.string.download_select_all);
        groupSelect.setTextSize(12);
        groupSelect.setChecked(groupAll);
        groupSelect.setMinWidth(0);
        groupSelect.setMinimumWidth(0);
        groupSelect.setPadding(0, 0, 0, 0);
        groupSelect.setOnClickListener(view -> selectGroup(items, groupSelect.isChecked()));
        header.addView(groupSelect);

        boolean expand = isExpanded(vodName, items.size());
        TextView toggle = text(getString(expand ? R.string.download_collapse : R.string.download_expand), 13, colorPrimary(), false);
        toggle.setPadding(dp(8), dp(6), 0, dp(6));
        toggle.setOnClickListener(view -> {
            expanded.put(vodName, !isExpanded(vodName, items.size()));
            render();
        });
        header.addView(toggle);
        card.addView(header);

        if (expand) card.addView(chipGrid(items));
        return card;
    }

    /** 单部剧的整组选择/取消。 */
    private void selectGroup(List<DownloadTask> items, boolean checked) {
        for (DownloadTask task : items) {
            if (checked) selected.add(task.getId());
            else selected.remove(task.getId());
        }
        render();
    }

    /** 全部剧集一键收起/展开。 */
    private void toggleAllGroups() {
        Map<String, List<DownloadTask>> groups = groupsOf(visibleTasks());
        if (groups.isEmpty()) return;
        boolean anyCollapsed = false;
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) {
            if (!isExpanded(entry.getKey(), entry.getValue().size())) anyCollapsed = true;
        }
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) expanded.put(entry.getKey(), anyCollapsed);
        render();
    }

    private boolean isExpanded(String vodName, int count) {
        Boolean value = expanded.get(vodName);
        return value == null ? count <= 8 : value;
    }

    private View chipGrid(List<DownloadTask> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(CHIP_COLUMNS);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        grid.setLayoutParams(params);
        int pad = dp(3);
        for (DownloadTask task : items) {
            GridLayout.LayoutParams chipParams = new GridLayout.LayoutParams();
            chipParams.width = 0;
            chipParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            chipParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            chipParams.setMargins(pad, pad, pad, pad);
            grid.addView(chip(task), chipParams);
        }
        return grid;
    }

    private View chip(DownloadTask task) {
        boolean isSelected = selected.contains(task.getId());
        LinearLayoutCompat chip = new LinearLayoutCompat(this);
        chip.setOrientation(LinearLayoutCompat.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(8), dp(6), dp(8));
        chip.setBackground(chipBackground(task, isSelected));

        TextView label = text(chipLabel(task), 13, chipTextColor(task), task.getState() == DownloadTask.State.DONE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        chip.addView(label, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chipLabels.put(task.getId(), label);
        chipViews.put(task.getId(), chip);

        // 未进入多选模式时，右侧 × 直接删除单集
        if (selected.isEmpty()) {
            TextView close = text("×", 16, colorOnSurfaceVariant(), false);
            close.setPadding(dp(6), 0, dp(2), 0);
            close.setOnClickListener(view -> confirmDelete(task));
            chip.addView(close, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        chip.setOnClickListener(view -> {
            if (!selected.isEmpty()) {
                toggleSelect(task);
            } else {
                onChipClick(task);
            }
        });
        chip.setOnLongClickListener(view -> {
            toggleSelect(task);
            return true;
        });
        return chip;
    }

    private void toggleSelect(DownloadTask task) {
        if (!selected.remove(task.getId())) selected.add(task.getId());
        render();
    }

    private void confirmDelete(DownloadTask task) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_delete_one_title)
                .setMessage(getString(R.string.download_delete_one_msg, task.getVodName(), task.getEpisodeName()))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    selected.remove(task.getId());
                    DownloadManager.get().remove(task, true);
                    render();
                }).show();
    }

    private void onChipClick(DownloadTask task) {
        switch (task.getState()) {
            case DONE:
                play(task);
                break;
            case WAITING:
            case RUNNING:
                DownloadManager.get().pause(java.util.Collections.singletonList(task));
                break;
            case FAILED:
            case CANCELED:
            case MISSING:
                DownloadManager.get().resume(java.util.Collections.singletonList(task));
                break;
        }
    }

    private void play(DownloadTask task) {
        File file = DownloadTask.findLocal(task.getVodName(), task.getEpisodeName());
        if (file == null) {
            DownloadManager.get().reconcile();
            Notify.show(R.string.download_local_missing_online);
            return;
        }
        Notify.show(R.string.download_play_local);
        VideoActivity.startLocal(this, task.getSiteKey(), task.getVodId(), task.getVodName(), task.getVodPic(), task.getEpisodeName());
    }

    private void onDelete() {
        if (selected.isEmpty()) {
            Notify.show(R.string.download_select_required);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_delete_confirm_title)
                .setMessage(R.string.download_delete_confirm_msg)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    List<DownloadTask> targets = new ArrayList<>();
                    for (DownloadTask task : DownloadManager.get().tasks()) if (selected.contains(task.getId())) targets.add(task);
                    selected.clear();
                    for (DownloadTask task : targets) DownloadManager.get().remove(task, true);
                    render();
                }).show();
    }

    private String chipLabel(DownloadTask task) {
        String name = task.getEpisodeName();
        switch (task.getState()) {
            case DONE:
                return "✓ " + name;
            case RUNNING:
                return name + " " + task.getProgress() + "%" + (task.getSpeed() > 0 ? " · " + DownloadTask.formatSpeed(task.getSpeed()) : "");
            case WAITING:
                return name + " " + getString(R.string.download_state_waiting);
            case FAILED:
                return name + " " + getString(R.string.download_state_failed);
            case MISSING:
                return name + " " + getString(R.string.download_state_missing);
            default:
                return task.getProgress() > 0 ? name + " " + task.getProgress() + "% · " + getString(R.string.download_state_paused) : name + " " + getString(R.string.download_state_paused);
        }
    }

    private int chipTextColor(DownloadTask task) {
        switch (task.getState()) {
            case DONE:
                return COLOR_DONE;
            case FAILED:
            case MISSING:
                return COLOR_ERROR;
            case CANCELED:
                return colorOnSurfaceVariant();
            default:
                return colorPrimary();
        }
    }

    private GradientDrawable chipBackground(DownloadTask task, boolean isSelected) {
        GradientDrawable drawable = new GradientDrawable();
        if (isSelected) {
            drawable.setColor(colorPrimaryContainer());
            drawable.setStroke(dp(2), colorPrimary());
        } else if (task.getState() == DownloadTask.State.DONE) {
            drawable.setColor(COLOR_DONE_BG);
            drawable.setStroke(dp(1), 0x33188038);
        } else if (task.getState() == DownloadTask.State.FAILED || task.getState() == DownloadTask.State.MISSING) {
            drawable.setColor(COLOR_ERROR_BG);
            drawable.setStroke(dp(1), 0x33B3261E);
        } else {
            drawable.setColor(colorSurfaceVariant());
            drawable.setStroke(dp(1), colorOutline());
        }
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colorSurface());
        drawable.setStroke(dp(1), colorOutline());
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int attr(int resId) {
        return MaterialColors.getColor(mBinding.getRoot(), resId);
    }

    private int colorPrimary() {
        return attr(androidx.appcompat.R.attr.colorPrimary);
    }

    private int colorPrimaryContainer() {
        return attr(com.google.android.material.R.attr.colorPrimaryContainer);
    }

    private int colorSurface() {
        return attr(com.google.android.material.R.attr.colorSurface);
    }

    private int colorSurfaceVariant() {
        return attr(com.google.android.material.R.attr.colorSurfaceVariant);
    }

    private int colorOnSurface() {
        return attr(com.google.android.material.R.attr.colorOnSurface);
    }

    private int colorOnSurfaceVariant() {
        return attr(com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    private int colorOutline() {
        return attr(com.google.android.material.R.attr.colorOutline);
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
