package com.fongmi.android.tv.ui.presenter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (Product.getColumn() - 1));
        int base = ResUtil.getScreenWidth() - space;
        width = base / Product.getColumn();
        height = (int) (width / 0.75f);
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        History item = (History) object;
        boolean same = item.getVodName().equals(item.getVodRemarks());
        ViewHolder holder = (ViewHolder) viewHolder;
        setClickListener(holder.view, item);
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        String watchedTime = HistoryProgressFormatter.format(item.getPosition(), item.getDuration());
        String episode = item.getVodRemarks();
        boolean hasEpisode = !TextUtils.isEmpty(episode) && !TextUtils.isEmpty(watchedTime) && !same;
        // 已看行：优先显示「已看 第X集 · 具体时间」，没有集数时退化为「已看 时间」
        holder.binding.historyProgress.setText(hasEpisode
                ? holder.view.getContext().getString(R.string.history_watched_episode, episode, watchedTime)
                : holder.view.getContext().getString(R.string.history_watched_time, watchedTime));
        boolean showProgress = !delete && !watchedTime.isEmpty();
        holder.binding.historyProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        // 集数已并入已看行时不再重复显示 remark
        holder.binding.remark.setVisibility(!delete && !showProgress && !same ? View.VISIBLE : View.GONE);
        UpdateTrack track = UpdateTrack.find(item.getKey());
        int updateCount = track == null ? 0 : track.getCount();
        holder.binding.historyUpdate.setText(holder.view.getContext().getString(R.string.history_update_to, updateCount));
        holder.binding.historyUpdate.setVisibility(!delete && updateCount > 0 ? View.VISIBLE : View.GONE);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        Glide.with(holder.binding.image).clear(holder.binding.image);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterVodBinding binding;

        public ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
