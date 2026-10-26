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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.databinding.DialogAccountBinding;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncStore;
import com.fongmi.android.tv.playback.RemoteSyncConfig;
import com.fongmi.android.tv.playback.ViewingRecordSyncStore;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

public class AccountDialog extends BaseAlertDialog {

    private static final int ADMIN_TAP_COUNT = 7;
    private static final String DEFAULT_ADMIN_CODE = "webhtv";

    private DialogAccountBinding binding;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText serverInput;
    private Runnable callback;
    private int adminTaps;

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
        params.width = (int) (screenWidth * (land ? 0.64f : 0.94f));
        params.height = land ? (int) (screenHeight * 0.82f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = land ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.root.setLayoutParams(rootParams);
        binding.contentScroll.setMaxHeight(land ? Integer.MAX_VALUE : (int) (screenHeight * 0.58f));
        binding.title.setOnClickListener(v -> onTitleTap());
        render();
    }

    @Override
    protected void initEvent() {
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
            render();
            return;
        }
        if (AccountStore.server().isEmpty() && passwordInput != null && DEFAULT_ADMIN_CODE.equals(passwordInput.getText().toString())) {
            AccountStore.setAdminUnlocked(true);
            Notify.show(R.string.account_admin_unlocked);
            render();
            return;
        }
        Notify.show(R.string.account_admin_hint);
    }

    private void render() {
        binding.content.removeAllViews();
        if (!AccountStore.isLogin()) renderLogin();
        else renderAccount();
        if (AccountStore.isAdminUnlocked()) binding.content.addView(adminSection());
    }

    private void renderLogin() {
        binding.content.addView(sectionTitle(getString(R.string.account_section_login)));
        binding.content.addView(fieldRow(getString(R.string.account_username), false, view -> {}));
        binding.content.addView(fieldRow(getString(R.string.account_password), true, view -> {}));
        binding.content.addView(buttonRow(getString(R.string.account_login), true, view -> submit(true)));
        binding.content.addView(buttonRow(getString(R.string.account_register), false, view -> submit(false)));
        binding.content.addView(note(getString(R.string.account_login_note)));
    }

    private void renderAccount() {
        binding.content.addView(sectionTitle(getString(R.string.account_section_info)));
        binding.content.addView(infoRow(getString(R.string.account_username), AccountStore.name()));
        binding.content.addView(infoRow(getString(R.string.account_sync_state), syncSummary()));
        binding.content.addView(buttonRow(getString(R.string.account_rebind_sync), false, view -> bindSync(true)));
        binding.content.addView(buttonRow(getString(R.string.account_logout), false, view -> logout()));
    }

    private View adminSection() {
        LinearLayoutCompat section = section(getString(R.string.account_section_admin));
        section.addView(infoRow(getString(R.string.account_server), AuthApi.server()));
        section.addView(fieldRow(getString(R.string.account_server), false, view -> {}));
        section.addView(buttonRow(getString(R.string.account_server_save), true, view -> saveServer()));
        section.addView(note(getString(R.string.account_admin_note)));
        return section;
    }

    private void submit(boolean login) {
        if (usernameInput == null || passwordInput == null) return;
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Notify.show(R.string.account_input_required);
            return;
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
                bindSync(false);
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

    private void bindSync(boolean notify) {
        String server = AuthApi.server();
        if (server.isEmpty() || !AccountStore.isLogin()) {
            if (notify) Notify.show(R.string.account_server_missing);
            return;
        }
        RemoteSyncConfig config = new RemoteSyncConfig();
        config.name = getString(R.string.account_sync_source_name);
        config.enabled = true;
        config.url = server + "/api/playback/sync";
        config.token = AccountStore.token();
        config.syncOnStartup = true;
        config.intervalMinutes = 30;
        config.maxItems = 100;
        PlaybackRemoteSyncStore.upsert(config);
        ViewingRecordSyncStore.setEnabled(true);
        if (notify) Notify.show(R.string.account_bind_done);
        render();
    }

    private void logout() {
        AuthApi.logout(new AuthApi.Callback() {
            @Override
            public void success(com.google.gson.JsonObject data) {
                AccountStore.logout();
                Notify.show(R.string.account_logout_done);
                render();
            }

            @Override
            public void error(String message) {
                AccountStore.logout();
                Notify.show(R.string.account_logout_done);
                render();
            }
        });
    }

    private void saveServer() {
        if (serverInput == null) return;
        AuthApi.server(serverInput.getText().toString());
        Notify.show(R.string.account_server_saved);
        render();
    }

    private LinearLayoutCompat section(String title) {
        LinearLayoutCompat root = new LinearLayoutCompat(requireContext());
        root.setOrientation(LinearLayoutCompat.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(4));
        root.setBackground(rowBackground());
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        root.setLayoutParams(params);
        MaterialTextView view = text(title, 12, Color.parseColor("#5F6368"), true);
        root.addView(view);
        return root;
    }

    private View sectionTitle(String text) {
        MaterialTextView view = text(text, 12, Color.parseColor("#5F6368"), true);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = binding.content.getChildCount() == 0 ? 0 : dp(12);
        view.setLayoutParams(params);
        return view;
    }

    private View fieldRow(String hint, boolean password, View.OnClickListener action) {
        TextInputEditText edit = new TextInputEditText(requireContext());
        edit.setHint(hint);
        edit.setTextSize(14);
        edit.setMaxLines(1);
        if (password) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (hint.equals(getString(R.string.account_username))) usernameInput = edit;
        else if (hint.equals(getString(R.string.account_password))) passwordInput = edit;
        else if (hint.equals(getString(R.string.account_server))) {
            serverInput = edit;
            edit.setText(AccountStore.server());
        }
        edit.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
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
        root.addView(text(label, 14, Color.BLACK, true), new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialTextView right = text(value, 13, Color.parseColor("#5F6368"), false);
        right.setGravity(Gravity.END);
        right.setMaxLines(1);
        root.addView(right, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
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

    private String syncSummary() {
        int active = PlaybackRemoteSyncStore.activeCount();
        int total = PlaybackRemoteSyncStore.totalCount();
        return getString(R.string.account_sync_state_value, active, total);
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

    private GradientDrawable rowBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#F5F6F7"));
        drawable.setStroke(dp(1), Color.parseColor("#DADCE0"));
        drawable.setCornerRadius(dp(6));
        return drawable;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
