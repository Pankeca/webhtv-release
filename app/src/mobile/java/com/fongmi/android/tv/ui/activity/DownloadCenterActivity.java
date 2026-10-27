package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.download.DownloadManager;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.databinding.ActivityDownloadCenterBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.widget.EpisodeChipConfig;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
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

    // 下载中心固定使用深色配色：不跟随系统明暗主题，避免白底卡片与背景割裂
    private static final int COLOR_TEXT = 0xFFF3F0FA;
    private static final int COLOR_TEXT_DIM = 0xFFA9A2C0;
    private static final int COLOR_ACCENT = 0xFFFF6B72;
    private static final int COLOR_RUNNING = 0xFF8AB4FF;
    private static final int COLOR_DONE = 0xFF6FDD8B;
    private static final int COLOR_DONE_BG = 0xFF17301F;
    private static final int COLOR_ERROR = 0xFFFF8A80;
    private static final int COLOR_ERROR_BG = 0xFF3A1D1D;
    private static final int COLOR_CARD = 0xFF201C32;
    private static final int COLOR_CARD_BORDER = 0x26FFFFFF;
    private static final int COLOR_CHIP = 0xFF2A2540;
    private static final int COLOR_CHIP_SELECTED = 0xFF3B2E58;
    private static final int CHIP_TEXT_SP = 13;
    private static final int CHIP_MIN_HEIGHT_DP = 46;

    private ActivityDownloadCenterBinding mBinding;
    private final TextView[] tabLabels = new TextView[2];
    private final TextView[] tabCounts = new TextView[2];
    private final Set<String> selected = new HashSet<>();
    private final Map<String, Boolean> expanded = new LinkedHashMap<>();
    private final Map<String, TextView> chipLabels = new LinkedHashMap<>();
    private final Map<String, LinearLayoutCompat> chipViews = new LinkedHashMap<>();
    private final Map<String, TextView> groupSummaries = new LinkedHashMap<>();
    /** 每部剧已下载文件的占用空间（字节），render 时刷新一次，避免进度刷新时反复扫盘。 */
    private final Map<String, Long> groupSizes = new LinkedHashMap<>();
    /** 已观看进度：key = 剧名|集名，value = 百分比；仅本地/在线历史中 position>0 的集数。 */
    private final Map<String, Integer> watchPercents = new LinkedHashMap<>();
    private String renderSignature = "";
    private long storageAt;
    private long usedBytes = -1;
    private long freeBytes = -1;
    private int tabIndex;
    private int bottomTab = -1;
    private float downX;
    private float downY;
    private boolean swipeCandidate;

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

    /** 左右滑动切换页签：仅在明显横向拖动时接管手势，不影响列表纵向滚动和剧集点击。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swipeCandidate = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeCandidate) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    swipeCandidate = Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) * 1.6f;
                }
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (swipeCandidate && Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    swipeTab(dx < 0 ? 1 : -1);
                    swipeCandidate = false;
                    return true;
                }
                swipeCandidate = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                swipeCandidate = false;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void swipeTab(int direction) {
        int target = tabIndex + direction;
        if (target < 0 || target > 1) return;
        mBinding.tabs.selectTab(mBinding.tabs.getTabAt(target));
        View content = mBinding.scroll;
        if (content.getVisibility() != View.VISIBLE || content.getWidth() == 0) return;
        content.setTranslationX(direction * content.getWidth() * 0.15f);
        content.setAlpha(0.35f);
        content.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
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
        addTab(0, R.string.download_tab_active);
        addTab(1, R.string.download_tab_done);
        mBinding.tabs.selectTab(mBinding.tabs.getTabAt(0));
    }

    /** 自定义页签：文字固定宽度、数量固定槽位，切换或数量变化时文字不会左右偏移。 */
    private void addTab(int index, int titleRes) {
        LinearLayoutCompat row = new LinearLayoutCompat(this);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView label = text(getString(titleRes), 15, COLOR_TEXT_DIM, true);
        TextView count = text("0", 13, COLOR_TEXT_DIM, true);
        count.setMinWidth(dp(26));
        count.setGravity(Gravity.START);
        row.addView(label);
        LinearLayoutCompat.LayoutParams countParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.leftMargin = dp(4);
        row.addView(count, countParams);
        tabLabels[index] = label;
        tabCounts[index] = count;
        mBinding.tabs.addTab(mBinding.tabs.newTab().setCustomView(row));
    }

    private void updateTabColors() {
        for (int i = 0; i < tabLabels.length; i++) {
            int color = i == tabIndex ? COLOR_ACCENT : COLOR_TEXT_DIM;
            if (tabLabels[i] != null) tabLabels[i].setTextColor(color);
            if (tabCounts[i] != null) tabCounts[i].setTextColor(color);
        }
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
        groupSizes.clear();
        watchPercents.clear();
        mBinding.empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        mBinding.scroll.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.content.removeAllViews();

        // 剧集被删除后其选中态也应失效
        Set<String> alive = new HashSet<>();
        for (DownloadTask task : visible) alive.add(task.getId());
        selected.retainAll(alive);

        Map<String, List<DownloadTask>> groups = groupsOf(visible);
        refreshGroupSizes(groups);
        refreshWatchPercents(groups);
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
        layoutBottomBar();
        refreshStats();
        renderSignature = signature();
        updateStorage();
    }

    /** 顶部总览：已下载集数 / 总占用 / 可用空间。 */
    private void refreshStats() {
        int done = 0;
        for (DownloadTask task : DownloadManager.get().tasks()) if (task.getState() == DownloadTask.State.DONE) done++;
        mBinding.statDoneValue.setText(String.valueOf(done));
        mBinding.statUsedValue.setText(usedBytes >= 0 ? DownloadTask.formatSize(usedBytes) : "—");
        mBinding.statFreeValue.setText(freeBytes >= 0 ? DownloadTask.formatSize(freeBytes) : "—");
    }

    private Map<String, List<DownloadTask>> groupsOf(List<DownloadTask> tasks) {
        Map<String, List<DownloadTask>> groups = new LinkedHashMap<>();
        for (DownloadTask task : tasks) groups.computeIfAbsent(task.getVodName(), key -> new ArrayList<>()).add(task);
        return groups;
    }

    /** 统计每部剧已下载集数的实际占用空间。 */
    private void refreshGroupSizes(Map<String, List<DownloadTask>> groups) {
        for (Map.Entry<String, List<DownloadTask>> entry : groups.entrySet()) {
            long total = 0;
            for (DownloadTask task : entry.getValue()) {
                if (task.getState() != DownloadTask.State.DONE) continue;
                File file = DownloadTask.findLocal(task.getVodName(), task.getEpisodeName());
                if (file != null) total += file.length();
            }
            groupSizes.put(entry.getKey(), total);
        }
    }

    /** 读取观看进度：播放过且 position>0 的集数记为观看至百分比，其余视为未观看。 */
    private void refreshWatchPercents(Map<String, List<DownloadTask>> groups) {
        for (String vodName : groups.keySet()) {
            try {
                for (History history : History.findByName(vodName)) {
                    if (history.getPosition() <= 0 || history.getDuration() <= 0) continue;
                    int percent = (int) Math.min(100, Math.round(history.getPosition() * 100f / history.getDuration()));
                    if (percent <= 0) continue;
                    watchPercents.put(watchKey(vodName, history.getVodRemarks()), percent);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private String watchKey(String vodName, String episodeName) {
        return vodName + "|" + episodeName;
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
        long size = groupSizes.getOrDefault(items.get(0).getVodName(), 0L);
        if (size > 0) text += " · " + DownloadTask.formatSize(size);
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
            App.post(() -> {
                if (mBinding == null || isFinishing() || isDestroyed()) return;
                usedBytes = used;
                freeBytes = free;
                refreshStats();
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

    /** 底栏：下载中页全选紧挨「全部开始」，已下载页全选放在「删除」左侧，避免最左难以点击。 */
    private void layoutBottomBar() {
        if (bottomTab == tabIndex) return;
        bottomTab = tabIndex;
        LinearLayoutCompat bar = mBinding.bottomBar;
        bar.removeView(mBinding.selectAll);
        int index = tabIndex == 0 ? bar.indexOfChild(mBinding.startAll) + 1 : bar.indexOfChild(mBinding.delete);
        bar.addView(mBinding.selectAll, index);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(10));
        params.setMarginEnd(0);
        mBinding.selectAll.setLayoutParams(params);
        for (int id : new int[]{R.id.startAll, R.id.pauseAll, R.id.delete}) {
            View button = bar.findViewById(id);
            if (button == null || !(button.getLayoutParams() instanceof LinearLayoutCompat.LayoutParams)) continue;
            LinearLayoutCompat.LayoutParams buttonParams = (LinearLayoutCompat.LayoutParams) button.getLayoutParams();
            buttonParams.setMarginStart(dp(10));
            button.setLayoutParams(buttonParams);
        }
    }

    private void updateTabTitles() {
        int active = 0;
        int done = 0;
        for (DownloadTask task : DownloadManager.get().tasks()) {
            if (task.getState() == DownloadTask.State.DONE) done++;
            else active++;
        }
        if (tabCounts[0] != null) tabCounts[0].setText(String.valueOf(active));
        if (tabCounts[1] != null) tabCounts[1].setText(String.valueOf(done));
        updateTabColors();
    }

    private View groupCard(String vodName, List<DownloadTask> items) {
        DownloadTask first = items.get(0);
        LinearLayoutCompat card = new LinearLayoutCompat(this);
        card.setOrientation(LinearLayoutCompat.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
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
        header.addView(cover, new LinearLayoutCompat.LayoutParams(dp(58), dp(80)));

        LinearLayoutCompat info = new LinearLayoutCompat(this);
        info.setOrientation(LinearLayoutCompat.VERTICAL);
        info.setPadding(dp(12), 0, dp(8), 0);
        info.setLayoutParams(new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = text(vodName, 18, COLOR_TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        int done = 0;
        for (DownloadTask task : items) if (task.getState() == DownloadTask.State.DONE) done++;
        TextView summary = text(groupSummaryText(items), 15, COLOR_TEXT_DIM, false);
        summary.setPadding(0, dp(3), 0, 0);
        info.addView(summary);
        groupSummaries.put(vodName, summary);
        header.addView(info);

        // 每部剧的全选框
        boolean groupAll = true;
        for (DownloadTask task : items) if (!selected.contains(task.getId())) groupAll = false;
        CheckBox groupSelect = new CheckBox(this);
        groupSelect.setText(R.string.download_select_all);
        groupSelect.setTextSize(14);
        groupSelect.setTextColor(COLOR_TEXT);
        groupSelect.setButtonTintList(ColorStateList.valueOf(COLOR_TEXT));
        groupSelect.setChecked(groupAll);
        groupSelect.setMinWidth(0);
        groupSelect.setMinimumWidth(0);
        groupSelect.setPadding(0, 0, 0, 0);
        groupSelect.setOnClickListener(view -> selectGroup(items, groupSelect.isChecked()));
        header.addView(groupSelect);

        boolean expand = isExpanded(vodName, items.size());
        TextView toggle = text(getString(expand ? R.string.download_collapse : R.string.download_expand), 15, COLOR_ACCENT, false);
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
        grid.setColumnCount(EpisodeChipConfig.COLUMNS);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        grid.setLayoutParams(params);
        int pad = dp(EpisodeChipConfig.GAP_DP);
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
        chip.setPadding(dp(4), dp(6), dp(4), dp(6));
        chip.setMinimumHeight(dp(CHIP_MIN_HEIGHT_DP));
        chip.setBackground(chipBackground(task, isSelected));

        TextView label = text(chipLabel(task), CHIP_TEXT_SP, chipTextColor(task), task.getState() == DownloadTask.State.DONE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        chip.addView(label, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chipLabels.put(task.getId(), label);
        chipViews.put(task.getId(), chip);

        // 未进入多选模式时，右侧 × 直接删除单集
        if (selected.isEmpty()) {
            TextView close = text("×", 15, COLOR_TEXT_DIM, false);
            close.setGravity(Gravity.CENTER);
            close.setPadding(dp(6), dp(4), dp(6), dp(4));
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
                // 已下载集数不再加 ✔，改为显示观看进度：观看至 % 或 未观看
                Integer percent = watchPercents.get(watchKey(task.getVodName(), task.getEpisodeName()));
                return name + " · " + (percent != null && percent > 0 ? getString(R.string.download_watched_to, percent) : getString(R.string.download_unwatched));
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
                return COLOR_TEXT_DIM;
            default:
                return COLOR_RUNNING;
        }
    }

    private GradientDrawable chipBackground(DownloadTask task, boolean isSelected) {
        GradientDrawable drawable = new GradientDrawable();
        if (isSelected) {
            drawable.setColor(COLOR_CHIP_SELECTED);
            drawable.setStroke(dp(2), COLOR_ACCENT);
        } else if (task.getState() == DownloadTask.State.DONE) {
            drawable.setColor(COLOR_DONE_BG);
            drawable.setStroke(dp(1), 0x336FDD8B);
        } else if (task.getState() == DownloadTask.State.FAILED || task.getState() == DownloadTask.State.MISSING) {
            drawable.setColor(COLOR_ERROR_BG);
            drawable.setStroke(dp(1), 0x33FF8A80);
        } else {
            drawable.setColor(COLOR_CHIP);
            drawable.setStroke(dp(1), 0x33FFFFFF);
        }
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(COLOR_CARD);
        drawable.setStroke(dp(1), COLOR_CARD_BORDER);
        drawable.setCornerRadius(dp(14));
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

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
