package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.download.DownloadTask;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeHoriHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeHoriBinding binding;
    private final int maxWidth;

    public EpisodeHoriHolder(@NonNull AdapterEpisodeHoriBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.maxWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(32);
    }

    @Override
    public void initView(Episode item) {
        binding.text.setMaxWidth(maxWidth);
        binding.text.setSelected(item.isSelected());
        binding.text.setText(isDownloaded(item) ? "✓ " + item.getDisplayName() : item.getDisplayName());
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }

    private boolean isDownloaded(Episode item) {
        EpisodeAdapter adapter = getBindingAdapter() instanceof EpisodeAdapter ? (EpisodeAdapter) getBindingAdapter() : null;
        String vodName = adapter == null ? "" : adapter.getVodName();
        return !TextUtils.isEmpty(vodName) && DownloadTask.findLocal(vodName, item.getName()) != null;
    }
}
