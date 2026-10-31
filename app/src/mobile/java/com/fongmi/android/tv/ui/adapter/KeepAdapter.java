package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.track.HistoryEpisodeCache;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.track.UpdateTracker;
import com.fongmi.android.tv.databinding.AdapterVodBinding;
import com.fongmi.android.tv.utils.HistoryProgressFormatter;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class KeepAdapter extends BaseDiffAdapter<Keep, KeepAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final Set<Keep> selected = new HashSet<>();
    private int width, height;
    private boolean delete;

    public KeepAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Keep item);

        void onItemSelect(Keep item);

        void onItemDelete(Keep item);

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
        this.delete = delete;
        if (!delete) selected.clear();
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isSelected(Keep item) {
        return selected.contains(item);
    }

    public void toggleSelection(Keep item) {
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

    /** 删除当前勾选项：全选是选中状态，只有点删除才真正移除。 */
    public void deleteSelected(Runnable done) {
        List<Keep> remain = new ArrayList<>(getItems());
        remain.removeAll(selected);
        for (Keep item : selected) item.delete();
        selected.clear();
        setItems(remain, done);
    }

    /** 删除模式下点海报上的垃圾桶图标：直接删除单条。 */
    public void deleteItem(Keep item, Runnable done) {
        List<Keep> remain = new ArrayList<>(getItems());
        remain.remove(item);
        selected.remove(item);
        item.delete();
        setItems(remain, done);
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        Keep.deleteAll();
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
        Keep item = getItem(position);
        holder.binding.name.setText(item.getVodName());
        holder.binding.remark.setVisibility(View.GONE);
        holder.binding.site.setVisibility(View.VISIBLE);
        holder.binding.site.setText(item.getSiteName());
        // 用 INVISIBLE 而不是 GONE：标签容器锚定在进度条上方，GONE 会让标签塌到海报顶部压住站点名
        holder.binding.progress.setVisibility(View.INVISIBLE);
        // 与「最近观看」保持同一套标签样式：已看第N集 / 更新至第N集
        History history = findHistory(item);
        String watchedTime = history == null ? "" : HistoryProgressFormatter.format(history.getPosition(), history.getDuration());
        String episode = history == null ? "" : HistoryProgressFormatter.episode(history.getVodRemarks());
        // 只要知道看到第几集就显示，没有播放进度时不再把整条标签藏掉
        boolean hasWatch = !TextUtils.isEmpty(episode) || !TextUtils.isEmpty(watchedTime);
        if (!episode.isEmpty() && !watchedTime.isEmpty()) {
            holder.binding.historyProgress.setText(holder.itemView.getContext().getString(R.string.history_watched_episode, episode, watchedTime));
        } else {
            holder.binding.historyProgress.setText(holder.itemView.getContext().getString(R.string.history_watched_time, episode.isEmpty() ? watchedTime : episode));
        }
        holder.binding.historyProgress.setVisibility(!delete && hasWatch ? View.VISIBLE : View.GONE);
        UpdateTrack track = UpdateTrack.find(item.getKey());
        int updateCount = Math.max(Math.max(track != null ? track.getCount() : 0, HistoryEpisodeCache.get(item.getKey())), UpdateTracker.parseEpisode(episode));
        holder.binding.historyUpdate.setText(holder.itemView.getContext().getString(R.string.history_update_to, updateCount));
        holder.binding.historyUpdate.setVisibility(!delete && updateCount > 0 ? View.VISIBLE : View.GONE);
        holder.binding.remark.setVisibility(UpdateTrack.hasUpdate(item.getKey()) ? View.VISIBLE : View.GONE);
        if (holder.binding.remark.getVisibility() == View.VISIBLE) {
            holder.binding.remark.setText(R.string.track_badge);
            holder.binding.remark.setTextColor(0xFFFF5252);
        }
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
        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    /** 追更条目对应的最近观看记录，用于显示已看集数与观看进度。 */
    private History findHistory(Keep item) {
        return History.forKeep(item);
    }

    private void setClickListener(View root, Keep item) {
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
