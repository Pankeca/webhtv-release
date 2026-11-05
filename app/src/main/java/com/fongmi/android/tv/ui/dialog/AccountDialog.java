package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.DialogAccountBinding;
import com.fongmi.android.tv.playback.KeepCloudBackup;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.AccountSync;
import com.fongmi.android.tv.setting.CloudConfigSync;
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.ui.custom.CustomNestedScrollView;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Personal center. Cloud sync binds automatically after login, so users only
 * choose which data is backed up. The admin block stays hidden until the title
 * is tapped seven times and the admin code is verified.
 */
public class AccountDialog extends BaseAlertDialog {

    private static final int ADMIN_TAP_COUNT = 7;

    private DialogAccountBinding binding;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText confirmInput;
    private TextInputEditText serverInput;
    private Runnable callback;
    private AlertDialog optionsDialog;
    private int adminTaps;
    /** 上次连点时间：间隔超过 3 秒就重新计数，避免误触累积到 7 次。 */
    private long adminTapTime;
    private boolean registerMode;
    private int lastWidth;
    /**
     * 转屏 / 分屏 / 改分辨率时窗口宽度是代码写死的，根布局宽度可能一点没变（甚至比屏幕还宽），
     * 光靠 addOnLayoutChangeListener 收不到通知。DisplayManager 的 onDisplayChanged 这几件事都会回调，
     * 比 ComponentCallbacks.onConfigurationChanged 更可靠（后者只在 Application 上才会被派发）。
     */
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            refreshMetrics();
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }
    };
    private boolean displayListenerBound;

    /** 等下一帧再算：回调触发时配置可能还没应用完，立刻取屏宽会拿到旧值。 */
    private void refreshMetrics() {
        if (binding == null) return;
        binding.getRoot().post(() -> {
            if (binding == null) return;
            applyWindowMetrics();
            render();
        });
    }

    public static void show(Fragment fragment, Runnable callback) {
        show(fragment.requireActivity(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        AccountDialog dialog = new AccountDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAccountBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        // 弹窗窗口默认带 ALT_FOCUSABLE_IM，会导致输入法无法弹出输入内容，这里必须清掉
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        // 允许输入法顶起窗口，避免输入框被软键盘遮挡
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        applyWindowMetrics();
        render();
        if (AccountStore.isLogin()) CloudConfigSync.refreshCounts(() -> {
            if (isAdded()) render();
        });
        // 手机横竖屏切换、平板分屏拖拽都不会重走 onStart，尺寸和分栏要自己跟着窗口变。
        // 只看宽度：内容重新排布会改高度，跟着高度重排会来回抖。
        binding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width <= 0 || width == lastWidth) return;
            lastWidth = width;
            applyWindowMetrics();
            render();
        });
        if (!displayListenerBound) {
            displayListenerBound = true;
            displayManager = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        }
    }

    /** 窗口尺寸与滚动区最大高度：横屏更宽更矮，竖屏更窄更高；分栏判断也在这里统一。 */
    private void applyWindowMetrics() {
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = isLandscape();
        params.width = (int) (screenWidth * (land ? (isWideLandscape() ? 0.78f : 0.86f) : 0.94f));
        // 转屏瞬间可能还拿着旧方向的屏宽，兜个底，别让窗口比屏幕还宽被裁掉
        params.width = Math.min(params.width, screenWidth);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        // 滚动区高度按实际可用屏高动态计算：竖屏多显示一些，横屏矮屏避免被系统栏裁掉；
        // 底部固定「关闭」占 48dp + 间距，预留高度相应加大，别把关闭按钮顶出屏幕
        int reserved = ResUtil.dp2px(land ? 150 : 250);
        int maxHeight = Math.max(ResUtil.dp2px(160), Math.min((int) (screenHeight * (land ? 0.66f : 0.58f)), screenHeight - reserved));
        binding.contentScroll.setMaxHeight(maxHeight);
    }

    private boolean isLandscape() {
        return ResUtil.isLand(requireContext());
    }

    /** 宽屏（平板横屏）才用双列，手机竖屏单列；手机横屏窗口窄，双列会挤成两行字。 */
    private boolean isWideLandscape() {
        return isLandscape() && ResUtil.getScreenWidth(requireContext()) >= ResUtil.dp2px(640);
    }

    @Override
    protected void initEvent() {
        // 底部整宽「关闭」：弹窗内容再长也能一屏点到，不用去找外面的空白区
        binding.close.setOnClickListener(view -> dismiss());
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (displayListenerBound) {
            displayListenerBound = false;
            try {
                if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
        }
        if (callback != null) callback.run();
        super.onDismiss(dialog);
    }

    /**
     * 管理员解锁热区：连点账号名 7 次（与 TVBox 一致）。
     * 第 3 次开始给出剩余次数提示，避免用户不知道要点几下。
     */
    private void onNameTap() {
        long now = System.currentTimeMillis();
        if (now - adminTapTime > 3000) adminTaps = 0;
        adminTapTime = now;
        adminTaps++;
        if (adminTaps >= ADMIN_TAP_COUNT) {
            adminTaps = 0;
            if (AccountStore.isAdminUnlocked()) {
                Notify.show(R.string.account_admin_already);
                return;
            }
            promptAdminCode();
        } else if (adminTaps >= 3) {
            Notify.show(getString(R.string.account_admin_tap_hint, ADMIN_TAP_COUNT - adminTaps));
        }
    }

    private void promptAdminCode() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.account_admin_code_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setShowSoftInputOnFocus(true);
        input.setOnClickListener(view -> Util.showKeyboard(input));
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) Util.showKeyboard(input);
        });
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_admin_code_title)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    if (BuildConfig.ADMIN_CODE.equals(input.getText().toString().trim())) {
                        AccountStore.setAdminUnlocked(true);
                        Notify.show(R.string.account_admin_unlocked);
                        render();
                    } else {
                        Notify.show(R.string.account_admin_code_wrong);
                    }
                }).show();
        // 同样清掉 ALT_FOCUSABLE_IM，保证管理员口令输入法能正常弹出
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void render() {
        binding.content.removeAllViews();
        usernameInput = null;
        passwordInput = null;
        confirmInput = null;
        serverInput = null;
        if (!AccountStore.isLogin()) {
            renderLogin();
        } else {
            // 与 TVBox 个人中心的顺序一致：账号卡 → 四格统计 → 立即同步/同步设置 → 管理员区 → 修改密码/退出/注销
            renderAccount();
            // 解锁状态会持久化（与 TVBox 一致）：已经是管理员就直接显示管理区，普通用户永远看不到
            if (AccountStore.isAdminUnlocked()) binding.content.addView(adminCard());
            binding.content.addView(securityCard());
        }
        binding.summary.setText(getString(AccountStore.isLogin() ? R.string.account_summary_login : R.string.account_summary_guest));
    }

    private void renderLogin() {
        binding.content.addView(headerCard(getString(R.string.account_state_logged_out), getString(R.string.account_login_note), false));
        LinearLayoutCompat card = card(getString(R.string.account_section_login));
        card.addView(fieldRow(getString(R.string.account_username), false));
        card.addView(fieldRow(getString(R.string.account_password), true));
        if (registerMode) card.addView(fieldRow(getString(R.string.account_password_confirm), true));
        card.addView(buttonRow(getString(registerMode ? R.string.account_register : R.string.account_login), true, view -> submit(!registerMode)));
        card.addView(buttonRow(getString(registerMode ? R.string.account_switch_login : R.string.account_switch_register), false, view -> {
            registerMode = !registerMode;
            render();
        }));
        card.addView(note(getString(R.string.account_auto_bind_note)));
        binding.content.addView(card);
    }

    private void renderAccount() {
        binding.content.addView(heroCard());
        binding.content.addView(statsCard());
        binding.content.addView(actionsCard());
    }

    /** 账号卡：头像 + 「当前账号」+ 账号名（管理员解锁热区）+ UID + 已登录角标，与 TVBox 的账号卡对齐。 */
    private View heroCard() {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#FFFFFF"), Color.parseColor("#EDF4FF")});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.parseColor("#E1EAF8"));
        root.setBackground(background);
        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(6);
        root.setLayoutParams(rootParams);

        LinearLayoutCompat head = new LinearLayoutCompat(requireContext());
        head.setOrientation(LinearLayoutCompat.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(avatar(AccountStore.name()), new LinearLayoutCompat.LayoutParams(dp(50), dp(50)));

        LinearLayoutCompat texts = new LinearLayoutCompat(requireContext());
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        texts.addView(text(getString(R.string.account_current), 11, Color.parseColor("#6B7280"), false));
        // 账号名兼任管理员解锁热区：连点 7 次（与 TVBox 的交互一致）
        String displayName = AccountStore.name();
        if (AccountStore.isAdminUnlocked()) displayName += getString(R.string.account_admin_suffix);
        MaterialTextView name = text(displayName, 18, Color.parseColor("#1A1C1E"), true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_VERTICAL);
        // 账号名兼任管理员解锁热区，点击区按 48dp 起，保证手机上不会点偏
        name.setMinimumHeight(dp(48));
        name.setClickable(true);
        name.setFocusable(true);
        name.setOnClickListener(view -> onNameTap());
        texts.addView(name);
        MaterialTextView sub = text(getString(R.string.account_uid, AccountStore.uid()), 11, Color.parseColor("#6B7280"), false);
        sub.setMaxLines(1);
        LinearLayoutCompat.LayoutParams subParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(2);
        texts.addView(sub, subParams);
        LinearLayoutCompat.LayoutParams textParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textParams.leftMargin = dp(12);
        head.addView(texts, textParams);
        LinearLayoutCompat.LayoutParams badgeParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.leftMargin = dp(8);
        head.addView(badge(getString(R.string.account_badge_login), "#E7F5EC", "#177245"), badgeParams);
        // 横向头部必须撑满卡片宽度，否则 wrap 宽度下列名会被压缩到 UID 的宽度而出现省略号
        root.addView(head, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /**
     * 四格统计 + 配置备份行。采用 2×2 排布（与 TVBox 一致），每格「本地 N · 云端 N」单行显示；
     * 一行四格在手机竖屏放不下，只能靠缩字号硬塞，这里改成两行换字号不缩。
     */
    private View statsCard() {
        LinearLayoutCompat stats = new LinearLayoutCompat(requireContext());
        stats.setOrientation(LinearLayoutCompat.VERTICAL);
        GradientDrawable statsBg = new GradientDrawable();
        statsBg.setColor(Color.parseColor("#F3F7FE"));
        statsBg.setCornerRadius(dp(14));
        stats.setBackground(statsBg);
        stats.setPadding(dp(4), dp(10), dp(4), dp(10));
        LinearLayoutCompat.LayoutParams statsParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsParams.topMargin = dp(10);
        stats.setLayoutParams(statsParams);

        stats.addView(statPair(
                statCell(getString(R.string.account_stats_keep), keepCount(), CloudSyncStore.cloudKeepCount(), view -> {
                    dismiss();
                    openActivity("com.fongmi.android.tv.ui.activity.KeepActivity");
                }),
                statCell(getString(R.string.account_stats_history), AccountSync.localHistoryCount(), CloudSyncStore.cloudHistoryCount(), view -> {
                    dismiss();
                    openActivity("com.fongmi.android.tv.ui.activity.HistoryActivity");
                })));
        LinearLayoutCompat.LayoutParams secondParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondParams.topMargin = dp(4);
        LinearLayoutCompat second = statPair(
                statCell(getString(R.string.account_stats_vod), CloudConfigSync.localVodCount(), CloudSyncStore.cloudVodCount(), view -> showSyncOptions()),
                statCell(getString(R.string.account_stats_live), CloudConfigSync.localLiveCount(), CloudSyncStore.cloudLiveCount(), view -> showSyncOptions()));
        second.setLayoutParams(secondParams);
        stats.addView(second);

        // 配置备份：本地是否已把点播源/直播源备份到云端（TVBox 的「已备份 / 未备份」）
        LinearLayoutCompat configRow = new LinearLayoutCompat(requireContext());
        configRow.setOrientation(LinearLayoutCompat.HORIZONTAL);
        configRow.setGravity(Gravity.CENTER_VERTICAL);
        configRow.setPadding(dp(10), 0, dp(10), 0);
        configRow.setMinimumHeight(dp(48));
        configRow.setBackgroundResource(R.drawable.ripple_round_14);
        configRow.setClickable(true);
        configRow.setFocusable(true);
        configRow.setOnClickListener(view -> showSyncOptions());
        configRow.addView(text(getString(R.string.account_stats_config), 12, Color.parseColor("#5F6368"), false), new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialTextView configValue = text(getString(CloudSyncStore.cloudConfigCount() > 0 ? R.string.account_config_backup_yes : R.string.account_config_backup_no), 13, Color.parseColor("#111827"), true);
        configValue.setMaxLines(1);
        configRow.addView(configValue, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayoutCompat.LayoutParams configParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        configParams.topMargin = dp(4);
        configRow.setLayoutParams(configParams);
        stats.addView(configRow);
        return stats;
    }

    /** 两格并排，等宽。 */
    private LinearLayoutCompat statPair(View left, View right) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.addView(left, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayoutCompat.LayoutParams rightParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rightParams.leftMargin = dp(4);
        row.addView(right, rightParams);
        return row;
    }

    /** 一格统计：名称在上，「本地 N · 云端 N」单行在下；整格可点，直接跳到对应页面。 */
    private View statCell(String label, int local, int cloud, View.OnClickListener listener) {
        LinearLayoutCompat column = new LinearLayoutCompat(requireContext());
        column.setOrientation(LinearLayoutCompat.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(10), dp(8), dp(10), dp(8));
        column.setMinimumHeight(dp(56));
        column.setClickable(true);
        column.setFocusable(true);
        column.setBackgroundResource(R.drawable.ripple_round_14);
        column.setOnClickListener(listener);
        MaterialTextView title = text(label, 11, Color.parseColor("#5F6368"), false);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(title);
        MaterialTextView valueView = text(getString(R.string.account_stats_value, local, cloud), 13, Color.parseColor("#111827"), true);
        valueView.setGravity(Gravity.CENTER);
        valueView.setMaxLines(1);
        // 云端条数可能到三四位数：宽度不够时只缩字号，不截断
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(valueView, 9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayoutCompat.LayoutParams valueParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(3);
        column.addView(valueView, valueParams);
        return column;
    }

    /**
     * 一级操作：立即同步 / 同步设置并排，管理员多一颗整宽「发布共享订阅」。
     * 旧的「云端同步」「配置中心」聚合入口已删除，点击直接进对应操作。
     */
    private View actionsCard() {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(10);
        root.setLayoutParams(rootParams);

        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        MaterialButton syncNow = createButton(getString(R.string.account_sync_now), true);
        MaterialButton syncSettings = createButton(getString(R.string.account_sync_settings), false);
        row.addView(syncNow, new LinearLayoutCompat.LayoutParams(0, dp(48), 1));
        LinearLayoutCompat.LayoutParams settingsParams = new LinearLayoutCompat.LayoutParams(0, dp(48), 1);
        settingsParams.leftMargin = dp(8);
        row.addView(syncSettings, settingsParams);
        syncNow.setOnClickListener(view -> syncWithCloud());
        syncSettings.setOnClickListener(view -> showSyncOptions());
        root.addView(row);
        return root;
    }

    /** 小圆角药丸：状态、角标都用它，统一视觉。 */
    private View badge(String text, String bg, String fg) {
        MaterialTextView view = text(text, 11, Color.parseColor(fg), true);
        view.setMaxLines(1);
        view.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(bg));
        drawable.setCornerRadius(dp(10));
        view.setBackground(drawable);
        return view;
    }

    /** 用类名跳转：HistoryActivity 只在 mobile 源集里，main 直接 import 会让 leanback 编译不过。 */
    private void openActivity(String className) {
        try {
            Intent intent = new Intent();
            intent.setClassName(requireContext(), className);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private LinearLayoutCompat optionsContent() {
        LinearLayoutCompat content = new LinearLayoutCompat(requireContext());
        content.setOrientation(LinearLayoutCompat.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), 0);
        return content;
    }

    private void showOptionsDialog(CharSequence title, View content) {
        // 二级菜单条目多，横屏矮屏会被裁掉：统放进可滚动容器，按屏高限制最大高度
        CustomNestedScrollView scroll = new CustomNestedScrollView(requireContext());
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        int maxHeight = (int) (screenHeight * (ResUtil.isLand(requireContext()) ? 0.52f : 0.62f));
        scroll.setMaxHeight(Math.max(ResUtil.dp2px(160), maxHeight));
        optionsDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton(R.string.dialog_close, (dialog, which) -> dialog.dismiss())
                .setOnDismissListener(dialog -> {
                    optionsDialog = null;
                    if (isAdded()) render();
                })
                .show();
        if (optionsDialog.getWindow() != null) optionsDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void dismissOptions() {
        if (optionsDialog != null) optionsDialog.dismiss();
        optionsDialog = null;
    }

    /** 云端同步二级菜单：备份开关、上传策略与恢复策略。 */
    private void showSyncOptions() {
        long keepAt = CloudSyncStore.keepBackupAt();
        long historyAt = CloudSyncStore.historyBackupAt();
        int keeps = keepCount();
        LinearLayoutCompat content = optionsContent();
        content.addView(switchRow(getString(R.string.account_backup_keep), keepAt > 0 ? getString(R.string.account_keep_desc_synced, keeps, formatTime(keepAt)) : getString(R.string.account_desc_never), CloudSyncStore.isKeepEnabled(), checked -> {
            AccountSync.setKeepBackup(checked);
            Notify.show(checked ? R.string.account_backup_on : R.string.account_backup_off);
        }));
        content.addView(switchRow(getString(R.string.account_backup_history), historyAt > 0 ? getString(R.string.account_history_desc_synced, formatTime(historyAt)) : getString(R.string.account_desc_never), CloudSyncStore.isHistoryEnabled(), checked -> {
            AccountSync.setHistoryBackup(checked);
            Notify.show(checked ? R.string.account_backup_on : R.string.account_backup_off);
        }));
        content.addView(switchRow(getString(R.string.account_backup_config), getString(R.string.account_backup_config_desc), CloudSyncStore.isConfigEnabled(), checked -> {
            CloudSyncStore.setConfigEnabled(checked);
            Notify.show(checked ? R.string.account_backup_on : R.string.account_backup_off);
        }));
        content.addView(infoRow(getString(R.string.account_sync_state), syncStatus()));

        // 同步方式已固定：登录新账号直接上传本地，老账号弹窗二选一（合并默认/覆盖云端），不再提供手动策略
        content.addView(note(getString(R.string.account_auto_bind_note)));
        showOptionsDialog(getString(R.string.account_sync_settings), content);
    }


    private LinearLayoutCompat securityCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_security));
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams firstParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        firstParams.topMargin = dp(2);
        LinearLayoutCompat.LayoutParams secondParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        secondParams.topMargin = dp(2);
        secondParams.leftMargin = dp(8);
        LinearLayoutCompat.LayoutParams thirdParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        thirdParams.topMargin = dp(2);
        thirdParams.leftMargin = dp(8);
        row.addView(chip(getString(R.string.account_change_password), "#F1F4F9", "#3C4043", view -> changePasswordPrompt()), firstParams);
        row.addView(chip(getString(R.string.account_logout), "#F1F4F9", "#3C4043", view -> logout()), secondParams);
        row.addView(chip(getString(R.string.account_delete), "#FDECEA", "#D93025", view -> confirmDeleteAccount()), thirdParams);
        card.addView(row);
        card.addView(note(getString(R.string.account_delete_note)));
        return card;
    }

    /** 等宽文字按钮：浅色底 + 圆角，一行放三个，比三条整宽链接省一半高度。 */
    private View chip(String label, String bg, String fg, View.OnClickListener listener) {
        MaterialTextView view = text(label, 13, Color.parseColor(fg), false);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        // 点击区不小于 48dp，手机上不会点偏
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(6), dp(9), dp(6), dp(9));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(bg));
        drawable.setCornerRadius(dp(12));
        view.setBackground(drawable);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(listener);
        return view;
    }

    /** 紧凑的可点击行：文字居中，代替整宽按钮，把一级菜单压进一屏。 */
    private View linkRow(String label, String color, View.OnClickListener listener) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.ripple_round_14);
        row.setOnClickListener(listener);
        MaterialTextView labelView = text(label, 14, Color.parseColor(color), false);
        labelView.setGravity(Gravity.CENTER);
        row.addView(labelView, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        params.topMargin = dp(2);
        row.setLayoutParams(params);
        return row;
    }

    private View adminCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_admin));
        // 管理员区与 TVBox 一致：发布共享订阅排在最前，随后是服务器、后端、更新
        card.addView(buttonRow(getString(R.string.account_publish_shared), false, view -> SharedConfigCenter.show(requireActivity())));
        card.addView(sectionLabel(getString(R.string.account_section_server)));
        card.addView(fieldRow(getString(R.string.account_server), false));
        card.addView(buttonRow(getString(R.string.account_server_save), true, view -> saveServer()));
        if (AccountStore.hasServerOverride()) {
            card.addView(buttonRow(getString(R.string.account_server_reset), false, view -> {
                AuthApi.server("");
                AccountSync.bind();
                Notify.show(R.string.account_server_saved);
                render();
            }));
        }
        // 旧「一键发布订阅 / 公共配置发布 / 公共目录」入口已下线，发布统一走账号卡片上的「发布共享订阅」
        card.addView(sectionLabel(getString(R.string.account_section_backend)));
        card.addView(buttonRow(getString(R.string.account_admin_backend), false, view -> backendLogin()));
        card.addView(buttonRow(getString(R.string.account_admin_update_settings), false, view -> {
            FragmentActivity activity = requireActivity();
            dismiss();
            activity.getWindow().getDecorView().post(() -> UpdateSettingsDialog.show(activity));
        }));
        card.addView(note(getString(R.string.account_admin_note)));
        return card;
    }

    private void submit(boolean login) {
        if (usernameInput == null || passwordInput == null) return;
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Notify.show(R.string.account_input_required);
            return;
        }
        if (!login) {
            String confirm = confirmInput == null ? "" : confirmInput.getText().toString();
            if (!password.equals(confirm)) {
                Notify.show(R.string.account_password_mismatch);
                return;
            }
        }
        if (AuthApi.server().isEmpty()) {
            Notify.show(R.string.account_server_missing);
            return;
        }
        binding.progress.setVisibility(View.VISIBLE);
        AuthApi.Callback callback = new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                binding.progress.setVisibility(View.GONE);
                AccountStore.login(data.get("uid").getAsLong(), username, data.get("token").getAsString());
                AccountSync.onLogin();
                Notify.show(R.string.account_welcome);
                render();
                if (login) promptLoginSyncWhenNeeded();
                else pushLocalToNewAccount();
            }

            @Override
            public void error(String message) {
                binding.progress.setVisibility(View.GONE);
                Notify.show(message);
            }
        };
        if (login) AuthApi.login(username, password, callback);
        else AuthApi.register(username, password, callback);
    }

    /** 同步完成的反馈：按已开启的备份项，把本次同步的条数/项数说明清楚。 */
    private String syncResultText() {
        List<String> parts = new ArrayList<>();
        if (CloudSyncStore.isKeepEnabled()) parts.add(getString(R.string.account_sync_part_keep, keepCount()));
        if (CloudSyncStore.isHistoryEnabled()) parts.add(getString(R.string.account_sync_part_history, AccountSync.localHistoryCount()));
        if (CloudSyncStore.isConfigEnabled()) parts.add(getString(R.string.account_sync_part_config, CloudConfigSync.localVodCount(), CloudConfigSync.localLiveCount()));
        return parts.isEmpty() ? getString(R.string.account_sync_result) : getString(R.string.account_sync_done_detail, TextUtils.join(" · ", parts));
    }

    /** 新账号：用户未登录期间产生的数据直接上传云端，不再询问。 */
    private void pushLocalToNewAccount() {
        if (!isAdded()) return;
        binding.progress.setVisibility(View.VISIBLE);
        AccountSync.applyLoginChoice(CloudSyncStore.MODE_LOCAL_FIRST, () -> {
            if (!isAdded()) return;
            binding.progress.setVisibility(View.GONE);
            render();
            Notify.show(syncResultText());
        });
    }

    /**
     * 登录后按云端状态走固定策略：
     * - 云端空（新建账号）：直接上传本地数据，不弹窗；
     * - 云端有数据（老账号）+ 本地有数据：弹窗二选一（合并默认 / 覆盖云端）；
     * - 云端有数据 + 本地空：静默按合并拉取。
     */
    private void promptLoginSyncWhenNeeded() {
        if (!isAdded()) return;
        int local = keepCount() + AccountSync.localHistoryCount();
        AccountSync.probeCloudData(hasCloudData -> {
            if (!isAdded()) return;
            if (!hasCloudData) {
                if (local > 0) pushLocalToNewAccount();
                return;
            }
            if (local == 0) {
                binding.progress.setVisibility(View.VISIBLE);
                AccountSync.applyLoginChoice(CloudSyncStore.MODE_MERGE, () -> {
                    if (!isAdded()) return;
                    binding.progress.setVisibility(View.GONE);
                    render();
                });
                return;
            }
            promptLoginSync();
        });
    }

    /** 老账号登录：默认高亮「合并」；取消则暂不同步，也不写入任何选择状态。 */
    private void promptLoginSync() {
        if (!isAdded()) return;
        int local = keepCount() + AccountSync.localHistoryCount();
        if (local == 0) return;
        final int[] mode = {CloudSyncStore.MODE_MERGE};
        String[] items = {getString(R.string.account_login_sync_merge), getString(R.string.account_login_sync_local)};
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_login_sync_title)
                .setMessage(R.string.account_login_sync_message)
                .setSingleChoiceItems(items, 0, (d, which) -> mode[0] = which == 0 ? CloudSyncStore.MODE_MERGE : CloudSyncStore.MODE_LOCAL_FIRST)
                .setNegativeButton(R.string.account_login_sync_later, null)
                .setPositiveButton(R.string.account_login_sync_confirm, (d, which) -> {
                    binding.progress.setVisibility(View.VISIBLE);
                    AccountSync.applyLoginChoice(mode[0], () -> {
                        if (!isAdded()) return;
                        binding.progress.setVisibility(View.GONE);
                        render();
                        Notify.show(R.string.account_sync_result);
                    });
                })
                .show();
    }

    /** 后端服务设置：管理员可在此修改云端服务地址、检测连通性并重建同步绑定。 */
    private void backendLogin() {
        if (!AccountStore.isAdminUnlocked()) {
            promptAdminCode();
            return;
        }
        LinearLayoutCompat content = optionsContent();
        content.addView(infoRow(getString(R.string.account_sync_state), syncStatus()));

        TextInputEditText serverField = passwordField(getString(R.string.account_server));
        serverField.setInputType(InputType.TYPE_CLASS_TEXT);
        serverField.setText(AuthApi.server());
        content.addView(serverField);
        content.addView(buttonRow(getString(R.string.account_server_save), true, view -> {
            AuthApi.server(serverField.getText().toString());
            AccountSync.bind();
            Notify.show(R.string.account_server_saved);
            dismissOptions();
            render();
        }));
        content.addView(buttonRow(getString(R.string.account_backend_health), false, view -> checkBackend()));
        content.addView(buttonRow(getString(R.string.account_backend_rebind), false, view -> {
            AccountSync.bind();
            CloudConfigSync.refreshCounts();
            Notify.show(R.string.account_backend_rebind_done);
        }));
        content.addView(note(getString(R.string.account_backend_note)));
        showOptionsDialog(getString(R.string.account_backend_title), content);
    }

    /** 检测后端 /api/health，确认服务地址可用。 */
    private void checkBackend() {
        Notify.show(R.string.account_backend_checking);
        AuthApi.health(new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                Notify.show(R.string.account_backend_health_ok);
            }

            @Override
            public void error(String message) {
                Notify.show(message);
            }
        });
    }

    private void changePasswordPrompt() {
        LinearLayoutCompat box = new LinearLayoutCompat(requireContext());
        box.setOrientation(LinearLayoutCompat.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        TextInputEditText oldInput = passwordField(getString(R.string.account_password_old));
        TextInputEditText newInput = passwordField(getString(R.string.account_password_new));
        box.addView(oldInput);
        box.addView(newInput);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_change_password)
                .setView(box)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> AuthApi.changePassword(oldInput.getText().toString(), newInput.getText().toString(), new AuthApi.Callback() {
                    @Override
                    public void success(com.google.gson.JsonObject data) {
                        if (data != null && data.has("token")) AccountStore.login(AccountStore.uid(), AccountStore.name(), data.get("token").getAsString());
                        Notify.show(R.string.account_password_changed);
                        render();
                    }

                    @Override
                    public void error(String message) {
                        Notify.show(message);
                    }
                })).show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private TextInputEditText passwordField(String hint) {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setHint(hint);
        edit.setTextSize(14);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        edit.setShowSoftInputOnFocus(true);
        edit.setOnClickListener(view -> Util.showKeyboard(edit));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        edit.setLayoutParams(params);
        return edit;
    }

    /** 注销账号：二次确认 + 密码校验，确认后永久删除云端数据。 */
    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_delete_title)
                .setMessage(R.string.account_delete_message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.account_delete_confirm, (d, which) -> promptDeletePassword())
                .show();
    }

    private void promptDeletePassword() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.account_delete_password);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setShowSoftInputOnFocus(true);
        input.setOnClickListener(view -> Util.showKeyboard(input));
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_delete_title)
                .setMessage(R.string.account_delete_message_final)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.account_delete_confirm, (d, which) -> {
                    String password = input.getText().toString();
                    if (TextUtils.isEmpty(password)) {
                        Notify.show(R.string.account_input_required);
                        return;
                    }
                    deleteAccount(password);
                }).show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void deleteAccount(String password) {
        binding.progress.setVisibility(View.VISIBLE);
        AuthApi.deleteAccount(password, new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                binding.progress.setVisibility(View.GONE);
                clearLocalLogin(false);
                Notify.show(R.string.account_delete_done);
            }

            @Override
            public void error(String message) {
                binding.progress.setVisibility(View.GONE);
                Notify.show(message);
            }
        });
    }

    private void logout() {
        AuthApi.logout(new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                clearLocalLogin();
            }

            @Override
            public void error(String message) {
                clearLocalLogin();
            }
        });
    }

    private void clearLocalLogin() {
        clearLocalLogin(true);
    }

    private void clearLocalLogin(boolean notify) {
        AccountStore.logout();
        AccountSync.unbind();
        adminTaps = 0;
        if (notify) Notify.show(R.string.account_logout_done);
        render();
    }

    private void saveServer() {
        if (serverInput == null) return;
        AuthApi.server(serverInput.getText().toString());
        AccountSync.bind();
        Notify.show(R.string.account_server_saved);
        render();
    }

    private String syncStatus() {
        if (!CloudSyncStore.isHistoryEnabled()) return getString(R.string.account_sync_off);
        return getString(R.string.account_sync_status_value, AccountSync.activeSources(), AccountSync.totalSources());
    }

    private int keepCount() {
        try {
            // 按剧名合并：同一部剧在不同站点/配置下只算一条，与列表展示口径一致
            List<String> names = new ArrayList<>();
            for (Keep keep : Keep.getVod()) names.add(keep.getVodName());
            for (Keep keep : Keep.getLive()) names.add(keep.getVodName());
            return CloudSyncStore.distinctNames(names);
        } catch (Throwable e) {
            return 0;
        }
    }

    private String formatTime(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private View headerCard(String title, String subtitle, boolean login) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#E8F0FE"), Color.parseColor("#F5F8FF")});
        background.setCornerRadius(dp(14));
        root.setBackground(background);
        root.addView(avatar(title), new LinearLayoutCompat.LayoutParams(dp(46), dp(46)));

        LinearLayoutCompat texts = new LinearLayoutCompat(requireContext());
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        MaterialTextView name = text(title, 17, Color.parseColor("#202124"), true);
        name.setMaxLines(1);
        texts.addView(name);
        MaterialTextView sub = text(subtitle, 12, Color.parseColor("#5F6368"), false);
        sub.setMaxLines(1);
        texts.addView(sub);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = dp(12);
        root.addView(texts, params);

        if (login) {
            View dot = new View(requireContext());
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.parseColor("#34A853"));
            dot.setBackground(dotBg);
            root.addView(dot, new LinearLayoutCompat.LayoutParams(dp(8), dp(8)));
        }

        LinearLayoutCompat.LayoutParams rootParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rootParams.topMargin = dp(8);
        root.setLayoutParams(rootParams);
        return root;
    }

    private View avatar(String name) {
        MaterialTextView view = new MaterialTextView(requireContext());
        String letter = TextUtils.isEmpty(name) ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault());
        view.setText(letter);
        view.setTextColor(Color.WHITE);
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.parseColor("#1A73E8"));
        view.setBackground(background);
        return view;
    }

    private LinearLayoutCompat card(String title) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(8));
        root.setBackground(cardBackground());
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        root.setLayoutParams(params);
        root.addView(sectionLabel(title));
        return root;
    }

    private View sectionLabel(String title) {
        MaterialTextView view = text(title, 12, Color.parseColor("#1A73E8"), true);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        params.bottomMargin = dp(2);
        view.setLayoutParams(params);
        return view;
    }

    private View switchRow(String title, String desc, boolean checked, SwitchRowListener listener) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(0, dp(8), 0, dp(8));

        LinearLayoutCompat texts = new LinearLayoutCompat(requireContext());
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        texts.addView(text(title, 14, Color.parseColor("#202124"), true));
        MaterialTextView summary = text(desc, 12, Color.parseColor("#80868B"), false);
        summary.setMaxLines(2);
        LinearLayoutCompat.LayoutParams summaryParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(2);
        summary.setLayoutParams(summaryParams);
        texts.addView(summary);
        root.addView(texts, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        SwitchMaterial toggle = new SwitchMaterial(requireContext());
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Color.parseColor("#1A73E8"), Color.parseColor("#C9CDD4")}));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) -> listener.onChanged(value));
        LinearLayoutCompat.LayoutParams toggleParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.leftMargin = dp(12);
        root.addView(toggle, toggleParams);
        return root;
    }

    private View fieldRow(String hint, boolean password) {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setHint(hint);
        edit.setTextSize(14);
        edit.setMaxLines(1);
        edit.setSingleLine(true);
        edit.setSelectAllOnFocus(false);
        if (password) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setShowSoftInputOnFocus(true);
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setClickable(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // 部分设备/电视盒子上输入框默认不主动拉起输入法，这里主动弹出，保证能输入
        edit.setOnClickListener(view -> Util.showKeyboard(edit));
        edit.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) Util.showKeyboard(edit);
        });
        edit.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) view.post(() -> Util.showKeyboard(edit));
            return false;
        });
        if (hint.equals(getString(R.string.account_username))) usernameInput = edit;
        else if (hint.equals(getString(R.string.account_password))) passwordInput = edit;
        else if (hint.equals(getString(R.string.account_password_confirm))) confirmInput = edit;
        else if (hint.equals(getString(R.string.account_server))) {
            serverInput = edit;
            edit.setText(AuthApi.server());
        }
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        edit.setLayoutParams(params);
        return edit;
    }

    /** 生成统一配色的按钮（高度 48dp，保证点击区），由调用方决定整宽还是并排等宽。 */
    private MaterialButton createButton(String label, boolean tonal) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(0, 0, 0, 0);
        ColorStateList bg = ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_bg : R.color.dialog_outlined_button_bg);
        ColorStateList fg = ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_text : R.color.dialog_outlined_button_text);
        button.setBackgroundTintList(bg);
        button.setTextColor(fg);
        if (!tonal) {
            button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
            button.setStrokeWidth(dp(1));
        }
        return button;
    }

    private View buttonRow(String label, boolean tonal, View.OnClickListener listener) {
        MaterialButton button = createButton(label, tonal);
        button.setOnClickListener(listener);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private View infoRow(String label, String value) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(text(label, 14, Color.parseColor("#3C4043"), false), new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialTextView right = text(value, 13, Color.parseColor("#5F6368"), false);
        right.setGravity(Gravity.END);
        right.setMaxLines(1);
        LinearLayoutCompat.LayoutParams rightParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rightParams.leftMargin = dp(12);
        root.addView(right, rightParams);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        root.setLayoutParams(params);
        return root;
    }

    /** 同步方式已固定为增量合并；「覆盖云端」只在登录旧账号的二选一弹窗里出现。 */
    private void syncWithCloud() {
        dismissOptions();
        binding.progress.setVisibility(View.VISIBLE);
        Notify.show(R.string.account_sync_running);
        AccountSync.sync(CloudSyncStore.MODE_MERGE, () -> {
            if (!isAdded()) return;
            binding.progress.setVisibility(View.GONE);
            render();
            Notify.show(syncResultText());
        });
    }

    private View note(String value) {
        MaterialTextView view = text(value, 12, Color.parseColor("#9AA0A6"), false);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private MaterialTextView text(String value, int sp, int color, boolean bold) {
        MaterialTextView view = new MaterialTextView(requireContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setSingleLine(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dp(1), Color.parseColor("#E4E7EB"));
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    private interface SwitchRowListener {

        void onChanged(boolean checked);
    }
}
