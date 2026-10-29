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
import com.fongmi.android.tv.track.HistoryEpisodeCache;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.track.UpdateTracker;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final Set<History> selected = new HashSet<>();
    private int width, height;
    private boolean animate;
    private boolean delete;

    public HistoryAdapter(OnClickListener listener) {
        this.listener = listener;
        this.animate = true;
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemSelect(History item);

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
        if (!delete) selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isSelected(History item) {
        return selected.contains(item);
    }

    public void toggleSelection(History item) {
        if (!selected.remove(item)) selected.add(item);
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setAllSelected(boolean checked) {
        selected.clear();
        if (checked) selected.addAll(getItems());
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isAllSelected() {
        return getItemCount() > 0 && selected.size() >= getItemCount();
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void clearSelection() {
        selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    /** 删除当前勾选项：全选只负责选中，删除需再点删除按钮确认。 */
    public void deleteSelected(Runnable done) {
        List<History> remain = new ArrayList<>(getItems());
        remain.removeAll(selected);
        for (History item : selected) item.deleteAndSync();
        selected.clear();
        setItems(remain, done);
    }

    /** 删除模式下点海报上的垃圾桶图标：直接删除单条。 */
    public void deleteItem(History item, Runnable done) {
        List<History> remain = new ArrayList<>(getItems());
        remain.remove(item);
        selected.remove(item);
        item.deleteAndSync();
        setItems(remain, done);
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
        if (delete) {
            boolean checked = selected.contains(item);
            // 未勾选时显示垃圾桶（点图标直接删单条），勾选后显示红底白勾，避免残留空心圈
            holder.binding.delete.setImageResource(checked ? R.drawable.ic_vod_check_on : R.drawable.ic_vod_delete);
            holder.binding.delete.setBackgroundColor(checked ? 0x40000000 : 0x80000000);
        } else {
            holder.binding.delete.setImageResource(R.drawable.ic_vod_delete);
            holder.binding.delete.setBackgroundColor(0x80000000);
        }
        holder.binding.delete.setOnClickListener(view -> {
            if (!isDelete()) return;
            if (selected.contains(item)) listener.onItemSelect(item);
            else listener.onItemDelete(item);
        });
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
        // 追更缓存、最近观看缓存、站点备注（如「更新至第12集」）任一有值即可显示最新集数
        int updateCount = Math.max(Math.max(track != null ? track.getCount() : 0, HistoryEpisodeCache.get(item.getKey())), UpdateTracker.parseEpisode(episode));
        // 只要知道最新集数就展示「更新至 第N集」，不再因已看集数追平而隐藏（用户明确需要看到该信息）
        boolean showUpdate = !delete && updateCount > 0;
        holder.binding.historyUpdate.setText(holder.itemView.getContext().getString(R.string.history_update_to, updateCount));
        holder.binding.historyUpdate.setVisibility(showUpdate ? View.VISIBLE : View.GONE);
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemSelect(item);
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
