package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.ActivityHistoryBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.track.HistoryEpisodeCache;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.track.UpdateTracker;
import com.fongmi.android.tv.ui.adapter.HistoryAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends BaseActivity implements HistoryAdapter.OnClickListener {

    private ActivityHistoryBinding mBinding;
    private HistoryAdapter mAdapter;
    private CheckBox mSelectAll;
    private boolean updatingSelectAll;
    private boolean autoChecked;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        getHistory();
    }

    private void setRecyclerView() {
        int column = MobileWindow.isWide(this) ? Product.getColumn(this) : 3;
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, column));
        mBinding.recycler.setAdapter(mAdapter = new HistoryAdapter(this));
        mAdapter.setSize(Product.getSpec(this, column));
    }

    private void getHistory() {
        mAdapter.setItems(History.get(), (hasChange) -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (hasChange) mBinding.recycler.scrollToPosition(0);
            syncSelectAll();
            autoCheckUpdates();
        });
    }

    /** 首次进入自动补全集数：只检查还没有「更新至」记录且未追更的条目，避免每次打开都全量请求。 */
    private void autoCheckUpdates() {
        if (autoChecked || mAdapter.getItemCount() == 0) return;
        autoChecked = true;
        List<UpdateTracker.Target> targets = new ArrayList<>();
        for (History item : mAdapter.getItems()) {
            int known = Math.max(UpdateTrack.find(item.getKey()) != null ? UpdateTrack.find(item.getKey()).getCount() : 0, HistoryEpisodeCache.get(item.getKey()));
            if (known > 0) continue;
            targets.add(new UpdateTracker.Target(item.getKey(), item.getSiteKey(), item.getVodId(), item.getVodName(), 0));
        }
        if (targets.isEmpty()) return;
        UpdateTracker.checkHistory(targets, message -> {
            if (!isFinishing()) getHistory();
        });
    }

    /** 手动更新：检查当前列表全部记录（含未追更），并提示更新详情。 */
    private void onUpdate() {
        List<UpdateTracker.Target> targets = new ArrayList<>();
        for (History item : mAdapter.getItems()) {
            UpdateTrack track = UpdateTrack.find(item.getKey());
            int known = track != null ? track.getCount() : Math.max(HistoryEpisodeCache.get(item.getKey()), UpdateTracker.parseEpisode(item.getVodRemarks()));
            targets.add(new UpdateTracker.Target(item.getKey(), item.getSiteKey(), item.getVodId(), item.getVodName(), known));
        }
        if (targets.isEmpty()) {
            Notify.show(R.string.track_update_none);
            return;
        }
        Notify.show(R.string.track_update_checking);
        UpdateTracker.checkHistory(targets, message -> {
            Notify.show(message);
            getHistory();
        });
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            if (mAdapter.getSelectedCount() == 0) {
                Notify.show(R.string.select_required);
                return;
            }
            new MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(getString(R.string.dialog_delete_selected, mAdapter.getSelectedCount())).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.deleteSelected(() -> {
                mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
                if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
                syncSelectAll();
            })).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
            syncSelectAll();
        }
    }

    private void syncSelectAll() {
        if (mSelectAll == null) return;
        boolean all = mAdapter.isAllSelected();
        if (mSelectAll.isChecked() != all) {
            updatingSelectAll = true;
            mSelectAll.setChecked(all);
            updatingSelectAll = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.HISTORY)) getHistory();
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, item.getWallPic());
    }

    @Override
    public void onItemSelect(History item) {
        mAdapter.toggleSelection(item);
        syncSelectAll();
    }

    /** 删除模式下点海报垃圾桶：直接删除该条。 */
    @Override
    public void onItemDelete(History item) {
        mAdapter.deleteItem(item, () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
            syncSelectAll();
        });
    }

    @Override
    public boolean onLongClick() {
        mAdapter.setDelete(!mAdapter.isDelete());
        syncSelectAll();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_history, menu);
        MenuItem item = menu.findItem(R.id.select_all);
        if (item != null && item.getActionView() instanceof CheckBox) {
            mSelectAll = (CheckBox) item.getActionView();
            mSelectAll.setOnCheckedChangeListener((buttonView, checked) -> {
                if (updatingSelectAll) return;
                if (checked && mAdapter.getItemCount() > 0) {
                    mAdapter.setDelete(true);
                    mAdapter.setAllSelected(true);
                } else if (!checked) {
                    mAdapter.clearSelection();
                }
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.update) onUpdate();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) {
            mAdapter.setDelete(false);
            syncSelectAll();
        } else super.onBackInvoked();
    }
}
