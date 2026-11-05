package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.databinding.DialogConfigBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ConfigDialog extends BaseAlertDialog {

    private DialogConfigBinding binding;
    private boolean append = true;
    private boolean edit;
    private String ori;
    private int type;
    private int lastWidth;
    private int lastMaxHeight = -1;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateWindowSize;

    public static ConfigDialog create() {
        return new ConfigDialog();
    }

    public ConfigDialog vod() {
        type = 0;
        return this;
    }

    public ConfigDialog live() {
        type = 1;
        return this;
    }

    public ConfigDialog wall() {
        type = 2;
        return this;
    }

    public ConfigDialog edit() {
        edit = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogConfigBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        Config config = getConfig();
        binding.title.setText(getDialogTitle());
        binding.positive.setText(edit ? R.string.dialog_edit : R.string.dialog_positive);
        binding.name.setText(edit ? config.getName() : "");
        binding.url.setText(ori = config.getUrl());
        binding.url.setSelection(TextUtils.isEmpty(ori) ? 0 : ori.length());
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
        binding.choose.setEndIconOnClickListener(this::onChoose);
        binding.url.addTextChangedListener(new CustomTextListener() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                detect(s.toString());
            }
        });
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
        binding.name.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onPositive();
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow();
        binding.url.requestFocus();
        // 键盘弹出/收起、旋转都会改变可见区域，跟随重算尺寸，保证底部按钮始终可达
        binding.scroll.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    public void onStop() {
        if (binding != null) binding.scroll.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        super.onStop();
    }

    private Config getConfig() {
        return switch (type) {
            case 0 -> VodConfig.get().getConfig();
            case 1 -> LiveConfig.get().getConfig();
            case 2 -> WallConfig.get().getConfig();
            default -> null;
        };
    }

    private Config getStoredConfig() {
        return switch (type) {
            case 0 -> Config.vod();
            case 1 -> Config.live();
            case 2 -> Config.wall();
            default -> Config.create(type);
        };
    }

    private int getTypeName() {
        return switch (type) {
            case 0 -> R.string.setting_vod;
            case 1 -> R.string.setting_live;
            case 2 -> R.string.setting_wall;
            default -> R.string.remote_trust_config_type;
        };
    }

    private String getDialogTitle() {
        int action = edit ? R.string.remote_trust_config_edit : R.string.remote_trust_config_add;
        return getString(R.string.setting_config_dialog_title, getString(action), getString(getTypeName()));
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show();
    }

    private void detect(String s) {
        if (append && "h".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ttp://");
        } else if (append && "f".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ile://");
        } else if (append && "a".equalsIgnoreCase(s)) {
            append = false;
            binding.url.append("ssets://");
        } else if (s.length() > 1) {
            append = false;
        } else if (s.isEmpty()) {
            append = true;
        }
    }

    private void onPositive() {
        String url = binding.url.getText().toString().trim();
        String name = binding.name.getText().toString().trim();
        Config config = saveConfig(url, name);
        if (config == null) {
            Notify.show(R.string.remote_trust_config_url_required);
            binding.url.requestFocus();
            return;
        }
        ((ConfigListener) requireParentFragment()).setConfig(config);
        dismiss();
    }

    private Config saveConfig(String url, String name) {
        Config config;
        if (url.isEmpty()) {
            if (!edit) return null;
            if (!TextUtils.isEmpty(ori)) Config.delete(ori, type);
            return getStoredConfig();
        } else if (edit) {
            config = Config.find(ori, type).url(url).name(name).update();
        } else {
            Config exists = AppDatabase.get().getConfigDao().find(url, type);
            config = exists != null ? exists : Config.create(type).url(url).name(name).update();
        }
        return config;
    }

    /**
     * 弹窗尺寸统一按「当前窗口可见区域」计算，而不是整屏宽度。
     *
     * <p>原实现用整屏宽 × 比例后再套一个固定 320dp 下限，窄屏/大字号/多窗口下
     * 下限可能超过实际可用宽度，卡片右侧被顶出屏幕，底部按钮也点不到。
     * 这里改为：宽度 = 可见宽度 - 两侧留白（再受最大阅读宽度限制），
     * 高度不超过可见高度，超出部分交给内部 ScrollView 滚动。
     */
    private void configureWindow() {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.setAttributes(params);
        // 表单含输入框：既要让输入法能弹出，也要让窗口随键盘收缩，避免底部按钮被键盘挡住
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        updateWindowSize();
        // 首次布局前 getWindowVisibleDisplayFrame 可能是空，布局完成后再校正一次
        binding.scroll.post(this::updateWindowSize);
    }

    private void updateWindowSize() {
        if (getDialog() == null || getDialog().getWindow() == null || binding == null) return;
        Window window = getDialog().getWindow();
        Rect visible = new Rect();
        window.getDecorView().getWindowVisibleDisplayFrame(visible);
        if (visible.isEmpty()) return;
        int margin = ResUtil.dp2px(12);
        int width = Math.min(ResUtil.dp2px(560), Math.max(0, visible.width() - margin * 2));
        // 内容超过可见高度时锁定滚动区高度；横屏矮屏与键盘弹出都走这条路径
        int maxHeight = Math.max(ResUtil.dp2px(120), visible.height() - margin * 2);
        if (width <= 0) return;
        if (maxHeight != lastMaxHeight) {
            lastMaxHeight = maxHeight;
            binding.scroll.setMaxHeight(maxHeight);
        }
        if (width != lastWidth) {
            lastWidth = width;
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String name = binding.name.getText().toString().trim();
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path)) return;
        String url = "file:/" + path.replace(Path.rootPath(), "");
        ((ConfigListener) requireParentFragment()).setConfig(saveConfig(url, name));
        dismiss();
    });
}
