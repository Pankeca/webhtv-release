package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.DialogAccountBinding;
import com.fongmi.android.tv.playback.KeepCloudBackup;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.AccountSync;
import com.fongmi.android.tv.setting.CloudConfigSync;
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.text.SimpleDateFormat;
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
    private static final String PUBLIC_SUBSCRIPTION_KEY = "subscriptions.json";

    private DialogAccountBinding binding;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText confirmInput;
    private TextInputEditText serverInput;
    private Runnable callback;
    private AlertDialog optionsDialog;
    private boolean syncConfigVod = true;
    private boolean syncConfigLive = true;
    private boolean syncConfigSettings = true;
    private int adminTaps;
    private boolean adminRevealed;
    private boolean registerMode;

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
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.62f : 0.94f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        // 弹窗窗口默认带 ALT_FOCUSABLE_IM，会导致输入法无法弹出输入内容，这里必须清掉
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        // 允许输入法顶起窗口，避免输入框被软键盘遮挡
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        binding.contentScroll.setMaxHeight((int) (screenHeight * 0.62f));
        render();
        if (AccountStore.isLogin()) CloudConfigSync.refreshCounts(() -> {
            if (isAdded()) render();
        });
    }

    @Override
    protected void initEvent() {
        binding.title.setOnClickListener(view -> onTitleTap());
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (callback != null) callback.run();
        super.onDismiss(dialog);
    }

    private void onTitleTap() {
        adminTaps++;
        if (adminTaps < ADMIN_TAP_COUNT) return;
        adminTaps = 0;
        if (AccountStore.isAdminUnlocked()) {
            adminRevealed = true;
            render();
            return;
        }
        promptAdminCode();
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
                        adminRevealed = true;
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
        if (!AccountStore.isLogin()) renderLogin();
        else renderAccount();
        if (adminRevealed && AccountStore.isAdminUnlocked()) binding.content.addView(adminCard());
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
        binding.content.addView(headerCard(AccountStore.name(), getString(R.string.account_uid, AccountStore.uid()), true));
        binding.content.addView(statsCard());
        binding.content.addView(entryRow(getString(R.string.account_section_sync), getString(R.string.account_entry_sync_desc), syncSummary(), view -> showSyncOptions()));
        binding.content.addView(entryRow(getString(R.string.account_section_config), getString(R.string.account_entry_config_desc), configSummary(), view -> showConfigOptions()));
        binding.content.addView(securityCard());
    }

    /** 概览：四宫格统计，本地/云端分开展示，视觉上更紧凑清晰。 */
    private View statsCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_stats));
        LinearLayoutCompat row1 = statGridRow(
                statTile(getString(R.string.account_stats_keep), keepCount(), CloudSyncStore.cloudKeepCount()),
                statTile(getString(R.string.account_stats_history), AccountSync.localHistoryCount(), CloudSyncStore.cloudHistoryCount()));
        LinearLayoutCompat.LayoutParams row1Params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Params.topMargin = dp(8);
        row1.setLayoutParams(row1Params);
        card.addView(row1);
        LinearLayoutCompat row2 = statGridRow(
                statTile(getString(R.string.account_stats_vod), CloudConfigSync.localVodCount(), CloudSyncStore.cloudVodCount()),
                statTile(getString(R.string.account_stats_live), CloudConfigSync.localLiveCount(), CloudSyncStore.cloudLiveCount()));
        LinearLayoutCompat.LayoutParams row2Params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Params.topMargin = dp(8);
        row2.setLayoutParams(row2Params);
        card.addView(row2);
        return card;
    }

    private LinearLayoutCompat statGridRow(View left, View right) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams leftParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(left, leftParams);
        LinearLayoutCompat.LayoutParams rightParams = new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rightParams.leftMargin = dp(8);
        row.addView(right, rightParams);
        return row;
    }

    private View statTile(String label, int local, int cloud) {
        LinearLayoutCompat tile = new LinearLayoutCompat(requireContext());
        tile.setOrientation(LinearLayoutCompat.VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#F4F7FC"));
        background.setCornerRadius(dp(10));
        tile.setBackground(background);
        tile.addView(text(label, 12, Color.parseColor("#5F6368"), false));
        MaterialTextView value = text(getString(R.string.account_stats_value, local, cloud), 14, Color.parseColor("#202124"), true);
        LinearLayoutCompat.LayoutParams valueParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(4);
        tile.addView(value, valueParams);
        return tile;
    }

    /** 二级菜单入口：标题 + 说明 + 摘要 + 右侧箭头。 */
    private View entryRow(String title, String desc, String summary, View.OnClickListener listener) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setBackground(cardBackground());
        row.setClickable(true);
        row.setOnClickListener(listener);
        LinearLayoutCompat.LayoutParams rowParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        row.setLayoutParams(rowParams);

        LinearLayoutCompat texts = new LinearLayoutCompat(requireContext());
        texts.setOrientation(LinearLayoutCompat.VERTICAL);
        texts.addView(text(title, 15, Color.parseColor("#202124"), true));
        MaterialTextView summaryView = text(summary, 12, Color.parseColor("#1A73E8"), false);
        LinearLayoutCompat.LayoutParams summaryParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(2);
        summaryView.setLayoutParams(summaryParams);
        summaryView.setMaxLines(1);
        texts.addView(summaryView);
        MaterialTextView descView = text(desc, 12, Color.parseColor("#80868B"), false);
        LinearLayoutCompat.LayoutParams descParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(2);
        descView.setLayoutParams(descParams);
        texts.addView(descView);
        row.addView(texts, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialTextView arrow = text("›", 24, Color.parseColor("#9AA0A6"), false);
        LinearLayoutCompat.LayoutParams arrowParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arrowParams.leftMargin = dp(10);
        row.addView(arrow, arrowParams);
        return row;
    }

    private String syncSummary() {
        return getString(R.string.account_entry_sync_state, keepCount(), AccountSync.localHistoryCount());
    }

    private String configSummary() {
        return getString(R.string.account_entry_config_state, CloudSyncStore.isConfigEnabled() ? getString(R.string.setting_on) : getString(R.string.setting_off));
    }

    private LinearLayoutCompat optionsContent() {
        LinearLayoutCompat content = new LinearLayoutCompat(requireContext());
        content.setOrientation(LinearLayoutCompat.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), 0);
        return content;
    }

    private void showOptionsDialog(CharSequence title, View content) {
        optionsDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(title)
                .setView(content)
                .setNegativeButton(R.string.dialog_close, null)
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

    /** 云端同步二级菜单：备份开关、状态与立即同步。 */
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
        content.addView(buttonRow(getString(R.string.account_sync_now), true, view -> {
            dismissOptions();
            syncNow();
        }));
        content.addView(note(getString(R.string.account_auto_bind_note)));
        showOptionsDialog(getString(R.string.account_section_sync), content);
    }

    /** 云端公共配置二级菜单：勾选内容项、冲突策略与拉取/备份。 */
    private void showConfigOptions() {
        LinearLayoutCompat content = optionsContent();
        content.addView(checkRow(getString(R.string.account_config_vod), syncConfigVod, checked -> syncConfigVod = checked));
        content.addView(checkRow(getString(R.string.account_config_live), syncConfigLive, checked -> syncConfigLive = checked));
        content.addView(checkRow(getString(R.string.account_config_settings), syncConfigSettings, checked -> syncConfigSettings = checked));
        content.addView(infoRow(getString(R.string.account_config_mode), conflictModeLabel()));
        content.addView(switchRow(getString(R.string.account_config_remember), getString(R.string.account_config_remember_desc), CloudSyncStore.isConflictRemembered(), checked -> CloudSyncStore.setConflictRemembered(checked)));
        content.addView(buttonRow(getString(R.string.account_config_pull), true, view -> {
            dismissOptions();
            pullPublicConfig();
        }));
        content.addView(buttonRow(getString(R.string.account_config_backup), false, view -> {
            dismissOptions();
            backupConfig();
        }));
        content.addView(note(getString(R.string.account_config_note)));
        showOptionsDialog(getString(R.string.account_section_config), content);
    }

    private LinearLayoutCompat securityCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_security));
        card.addView(buttonRow(getString(R.string.account_change_password), false, view -> changePasswordPrompt()));
        card.addView(buttonRow(getString(R.string.account_logout), false, view -> logout()));
        card.addView(dangerRow(getString(R.string.account_delete), view -> confirmDeleteAccount()));
        card.addView(note(getString(R.string.account_delete_note)));
        return card;
    }

    private View adminCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_admin));
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
        card.addView(sectionLabel(getString(R.string.account_section_public)));
        card.addView(buttonRow(getString(R.string.account_public_publish), false, view -> publishSubscriptions()));
        card.addView(buttonRow(getString(R.string.account_admin_publish_config), false, view -> publishConfig()));
        card.addView(buttonRow(getString(R.string.account_public_list), false, view -> listPublic()));
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
                if (login) promptLoginSync();
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

    private void syncNow() {
        // 已记住同步策略的用户不再重复提示，避免打扰
        if (CloudSyncStore.isConfigEnabled() && !CloudSyncStore.isConflictRemembered()) {
            new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                    .setTitle(R.string.account_sync_confirm_title)
                    .setMessage(R.string.account_sync_confirm_message)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (dialog, which) -> doSyncNow())
                    .show();
            return;
        }
        doSyncNow();
    }

    private void doSyncNow() {
        binding.progress.setVisibility(View.VISIBLE);
        Notify.show(R.string.account_sync_started);
        AccountSync.syncNow(() -> {
            if (!isAdded()) return;
            binding.progress.setVisibility(View.GONE);
            render();
            Notify.show(R.string.account_sync_result);
        });
    }

    /** 登录后提示是否把本地记录同步到云端，覆盖操作均需用户明确选择。 */
    private void promptLoginSync() {
        if (!isAdded()) return;
        int local = keepCount() + AccountSync.localHistoryCount();
        if (local == 0) return;
        final int[] mode = {CloudSyncStore.MODE_MERGE};
        String[] items = {getString(R.string.account_login_sync_merge), getString(R.string.account_login_sync_cloud), getString(R.string.account_login_sync_local)};
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_login_sync_title)
                .setMessage(R.string.account_login_sync_message)
                .setSingleChoiceItems(items, 0, (d, which) -> mode[0] = which)
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

    private void pullPublicConfig() {
        if (!syncConfigVod && !syncConfigLive && !syncConfigSettings) {
            Notify.show(R.string.account_config_none_selected);
            return;
        }
        chooseConflictMode(mode -> {
            binding.progress.setVisibility(View.VISIBLE);
            CloudConfigSync.pullPublic(syncConfigVod, syncConfigLive, syncConfigSettings, mode, new CloudConfigSync.Callback() {
                @Override
                public void success(String message) {
                    if (!isAdded()) return;
                    binding.progress.setVisibility(View.GONE);
                    render();
                    Notify.show(message);
                }

                @Override
                public void error(String message) {
                    if (!isAdded()) return;
                    binding.progress.setVisibility(View.GONE);
                    Notify.show(message);
                }
            });
        });
    }

    private void backupConfig() {
        if (!syncConfigVod && !syncConfigLive && !syncConfigSettings) {
            Notify.show(R.string.account_config_none_selected);
            return;
        }
        // 上传会用本地内容覆盖云端备份，先明确提示，避免误覆盖。
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_config_backup_confirm_title)
                .setMessage(R.string.account_config_backup_confirm_message)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> doBackupConfig())
                .show();
    }

    private void doBackupConfig() {
        binding.progress.setVisibility(View.VISIBLE);
        CloudConfigSync.backup(syncConfigVod, syncConfigLive, syncConfigSettings, new CloudConfigSync.Callback() {
            @Override
            public void success(String message) {
                if (!isAdded()) return;
                binding.progress.setVisibility(View.GONE);
                render();
                Notify.show(message);
            }

            @Override
            public void error(String message) {
                if (!isAdded()) return;
                binding.progress.setVisibility(View.GONE);
                Notify.show(message);
            }
        });
    }

    /** 未记住选择时弹出增量/覆盖选择，默认增量。 */
    private void chooseConflictMode(java.util.function.IntConsumer onDone) {
        if (CloudSyncStore.isConflictRemembered()) {
            onDone.accept(CloudSyncStore.conflictMode());
            return;
        }
        final int[] mode = {CloudSyncStore.MODE_MERGE};
        String[] items = {getString(R.string.account_config_mode_merge), getString(R.string.account_config_mode_cloud), getString(R.string.account_config_mode_local)};
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.account_config_mode)
                .setSingleChoiceItems(items, CloudSyncStore.conflictMode(), (d, which) -> mode[0] = which)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    CloudSyncStore.setConflictMode(mode[0]);
                    onDone.accept(mode[0]);
                })
                .show();
    }

    private void publishConfig() {
        if (!AccountStore.isAdminUnlocked()) return;
        CloudConfigSync.publish(true, true, true, new CloudConfigSync.Callback() {
            @Override
            public void success(String message) {
                Notify.show(message);
            }

            @Override
            public void error(String message) {
                Notify.show(message);
            }
        });
    }

    /** 后端服务登录：确认管理员权限后保持后端会话可用。 */
    private void backendLogin() {
        if (AccountStore.isAdminUnlocked()) {
            Notify.show(R.string.account_admin_backend_done);
        } else {
            promptAdminCode();
        }
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
        adminRevealed = false;
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

    private void publishSubscriptions() {
        if (!AccountStore.isAdminUnlocked()) return;
        List<Config> configs = Config.findUrls();
        String content = App.gson().toJson(configs);
        AuthApi.publicPut(PUBLIC_SUBSCRIPTION_KEY, content, new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                Notify.show(R.string.account_public_publish_done);
            }

            @Override
            public void error(String message) {
                Notify.show(message);
            }
        });
    }

    private void listPublic() {
        AuthApi.publicList(new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                int count = data.has("keys") ? data.getAsJsonArray("keys").size() : 0;
                Notify.show(getString(R.string.account_public_list_done, count));
            }

            @Override
            public void error(String message) {
                Notify.show(message);
            }
        });
    }

    private String syncStatus() {
        if (!CloudSyncStore.isHistoryEnabled()) return getString(R.string.account_sync_off);
        return getString(R.string.account_sync_status_value, AccountSync.activeSources(), AccountSync.totalSources());
    }

    private int keepCount() {
        try {
            return Keep.getVod().size() + Keep.getLive().size();
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

    private MaterialButton actionButton(String label, boolean tonal) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setSingleLine(true);
        button.setTextSize(14);
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
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(label);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(0, 0, 0, 0);
        ColorStateList bg = ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_bg : R.color.dialog_outlined_button_bg);
        ColorStateList fg = ContextCompat.getColorStateList(requireContext(), tonal ? R.color.dialog_tonal_button_text : R.color.dialog_outlined_button_text);
        button.setBackgroundTintList(bg);
        button.setTextColor(fg);
        if (!tonal) {
            button.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
            button.setStrokeWidth(dp(1));
        }
        button.setOnClickListener(listener);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
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

    /** 统计行：左侧标签，右侧「本地 N · 云端 M」右对齐。 */
    private View statRow(String label, int local, int cloud) {
        return infoRow(label, getString(R.string.account_stats_value, local, cloud));
    }

    private View checkRow(String label, boolean checked, SwitchRowListener listener) {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(text(label, 14, Color.parseColor("#3C4043"), false), new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        CheckBox box = new CheckBox(requireContext());
        box.setChecked(checked);
        box.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{Color.parseColor("#1A73E8"), Color.parseColor("#8A8F98")}));
        box.setOnCheckedChangeListener((button, value) -> listener.onChanged(value));
        row.setOnClickListener(view -> box.setChecked(!box.isChecked()));
        row.addView(box, new LinearLayoutCompat.LayoutParams(dp(36), dp(36)));
        return row;
    }

    private View dangerRow(String label, View.OnClickListener listener) {
        MaterialButton button = actionButton(label, false);
        button.setTextColor(Color.parseColor("#D93025"));
        button.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#E7B7B2")));
        button.setOnClickListener(listener);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private String conflictModeLabel() {
        int mode = CloudSyncStore.conflictMode();
        if (mode == CloudSyncStore.MODE_CLOUD_FIRST) return getString(R.string.account_config_mode_cloud);
        if (mode == CloudSyncStore.MODE_LOCAL_FIRST) return getString(R.string.account_config_mode_local);
        return getString(R.string.account_config_mode_merge);
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
