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
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.track.UpdateTrack;
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
    }

    @Override
    protected void initEvent() {
        binding.title.setOnClickListener(view -> onTitleTap());
        binding.negative.setText(R.string.account_close);
        binding.positive.setText(R.string.account_done);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.positive.setOnClickListener(view -> dismiss());
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
        int keeps = keepCount();
        int tracks = trackCount();
        long keepAt = CloudSyncStore.keepBackupAt();
        long historyAt = CloudSyncStore.historyBackupAt();

        LinearLayoutCompat sync = card(getString(R.string.account_section_sync));
        sync.addView(switchRow(getString(R.string.account_backup_keep), keepAt > 0 ? getString(R.string.account_keep_desc_synced, keeps, tracks, formatTime(keepAt)) : getString(R.string.account_desc_never), CloudSyncStore.isKeepEnabled(), checked -> {
            AccountSync.setKeepBackup(checked);
            Notify.show(checked ? R.string.account_backup_on : R.string.account_backup_off);
        }));
        sync.addView(switchRow(getString(R.string.account_backup_history), historyAt > 0 ? getString(R.string.account_history_desc_synced, formatTime(historyAt)) : getString(R.string.account_desc_never), CloudSyncStore.isHistoryEnabled(), checked -> {
            AccountSync.setHistoryBackup(checked);
            Notify.show(checked ? R.string.account_backup_on : R.string.account_backup_off);
        }));
        sync.addView(infoRow(getString(R.string.account_sync_state), syncStatus()));
        sync.addView(infoRow(getString(R.string.account_keep_state), getString(R.string.account_keep_state_value, keeps, tracks)));
        sync.addView(actionRow());
        sync.addView(note(getString(R.string.account_auto_bind_note)));
        binding.content.addView(sync);
    }

    private View adminCard() {
        LinearLayoutCompat card = card(getString(R.string.account_section_admin));
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
        card.addView(buttonRow(getString(R.string.account_public_list), false, view -> listPublic()));
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
                KeepCloudBackup.restoreIfEmpty();
                Notify.show(R.string.account_welcome);
                render();
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
        Notify.show(R.string.account_sync_started);
        AccountSync.syncNow(() -> {
            if (!isAdded()) return;
            CloudSyncStore.markHistoryBackup();
            render();
            Notify.show(R.string.account_sync_done);
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
        AccountStore.logout();
        AccountSync.unbind();
        adminRevealed = false;
        Notify.show(R.string.account_logout_done);
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

    private int trackCount() {
        try {
            return UpdateTrack.list().size();
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

    /** 底部操作行：立即同步（主操作）与退出登录（次要/危险操作）并排，避免两块整行按钮堆叠。 */
    private View actionRow() {
        LinearLayoutCompat row = new LinearLayoutCompat(requireContext());
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton sync = actionButton(getString(R.string.account_sync_now), true);
        MaterialButton logout = actionButton(getString(R.string.account_logout), false);
        int danger = Color.parseColor("#D93025");
        logout.setTextColor(danger);
        logout.setStrokeColor(ColorStateList.valueOf(Color.parseColor("#E7B7B2")));

        LinearLayoutCompat.LayoutParams syncParams = new LinearLayoutCompat.LayoutParams(0, dp(42), 1);
        LinearLayoutCompat.LayoutParams logoutParams = new LinearLayoutCompat.LayoutParams(0, dp(42), 1);
        logoutParams.leftMargin = dp(10);
        row.addView(sync, syncParams);
        row.addView(logout, logoutParams);

        sync.setOnClickListener(view -> syncNow());
        logout.setOnClickListener(view -> logout());

        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        row.setLayoutParams(params);
        return row;
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
        root.addView(right, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        root.setLayoutParams(params);
        return root;
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
