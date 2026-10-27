package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;

public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private int width, height;
    private boolean animate;
    private boolean delete;

    public HistoryAdapter(OnClickListener listener) {
        this.listener = listener;
        this.animate = true;
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    public void setSize(int[] size) {
        this.width = size[0];
        this.height = size[1];
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.animate = false;
        this.delete = delete;
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        History.deleteAndSync(VodConfig.getCid());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.image.getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History item = getItem(position);
        boolean same = item.getVodName().equals(item.getVodRemarks());
        holder.binding.name.setText(item.getVodName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.site.setVisibility(item.getSiteVisible());
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(0, item.getDuration()));
        int progress = (int) Math.min(Integer.MAX_VALUE, Math.max(0, item.getPosition()));
        holder.binding.progress.setMax(duration > 0 ? duration : 1);
        holder.binding.progress.setProgress(duration > 0 ? Math.min(progress, duration) : 0, animate);
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        String watchedTime = HistoryProgressFormatter.format(item.getPosition(), item.getDuration());
        String episode = item.getVodRemarks();
        boolean hasEpisode = !TextUtils.isEmpty(episode) && !TextUtils.isEmpty(watchedTime) && !same;
        // 已看行：优先显示「已看 第X集 · 具体时间」，没有集数时退化为「已看 时间」
        holder.binding.historyProgress.setText(hasEpisode
                ? holder.itemView.getContext().getString(R.string.history_watched_episode, episode, watchedTime)
                : holder.itemView.getContext().getString(R.string.history_watched_time, watchedTime));
        boolean showProgress = !delete && !watchedTime.isEmpty();
        holder.binding.historyProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        // 集数已并入已看行时不再重复显示 remark
        holder.binding.remark.setVisibility(!delete && !showProgress && !same ? View.VISIBLE : View.GONE);
        UpdateTrack track = UpdateTrack.find(item.getKey());
        int updateCount = track == null ? 0 : track.getCount();
        holder.binding.historyUpdate.setText(holder.itemView.getContext().getString(R.string.history_update_to, updateCount));
        holder.binding.historyUpdate.setVisibility(!delete && updateCount > 0 ? View.VISIBLE : View.GONE);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterVodBinding binding;

        ViewHolder(@NonNull AdapterVodBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
