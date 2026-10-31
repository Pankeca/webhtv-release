package com.fongmi.android.tv.bean;

import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.playback.PlaybackProgressWriter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Entity
public class History implements Diffable<History> {

    private static final long NEAR_END_MIN_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long NEAR_END_MAX_MS = TimeUnit.SECONDS.toMillis(30);

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    private String key;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("wallPic")
    private String wallPic;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodFlag")
    private String vodFlag;
    @SerializedName("vodRemarks")
    private String vodRemarks;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("revSort")
    private boolean revSort;
    @SerializedName("revPlay")
    private boolean revPlay;
    @SerializedName("createTime")
    private long createTime;
    @SerializedName("opening")
    private long opening;
    @SerializedName("ending")
    private long ending;
    @SerializedName("position")
    private long position;
    @SerializedName("duration")
    private long duration;
    @SerializedName("speed")
    private float speed;
    @SerializedName("scale")
    private int scale;
    @SerializedName("cid")
    private int cid;

    private transient long updateTime;

    public History() {
        this.speed = 1;
        this.scale = -1;
        this.ending = C.TIME_UNSET;
        this.opening = C.TIME_UNSET;
        this.position = C.TIME_UNSET;
        this.duration = C.TIME_UNSET;
    }

    public History copy() {
        History item = new History();
        item.key = key;
        item.vodPic = vodPic;
        item.wallPic = wallPic;
        item.vodName = vodName;
        item.vodFlag = vodFlag;
        item.vodRemarks = vodRemarks;
        item.episodeUrl = episodeUrl;
        item.revSort = revSort;
        item.revPlay = revPlay;
        item.createTime = createTime;
        item.opening = opening;
        item.ending = ending;
        item.position = position;
        item.duration = duration;
        item.speed = speed;
        item.scale = scale;
        item.cid = cid;
        item.updateTime = updateTime;
        return item;
    }

    public static History objectFrom(String str) {
        return App.gson().fromJson(str, History.class);
    }

    public static List<History> arrayFrom(String str) {
        Type listType = TypeToken.getParameterized(List.class, History.class).getType();
        List<History> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static List<History> get() {
        return get(VodConfig.getCid());
    }

    public static List<History> get(int cid) {
        List<History> items = AppDatabase.get().getHistoryDao().find(cid, System.currentTimeMillis() - Constant.HISTORY_TIME);
        return dedupe(items);
    }

    /**
     * 同一部剧只留最新一条，并把旧条真正删掉。
     *
     * <p>为什么要在读的时候做：老版本把 push（服务端注入 / 本地下载）排除在按剧名合并之外，
     * 库里已经堆了同一部剧的多条记录——站点一条、注入一条、本地下好的一条。
     * 光改保存逻辑只能拦住新增，历史遗留还得清一遍，否则「最近观看」永远有一堆重复卡片。
     *
     * <p>查询本身按 createTime 倒序，所以每个剧名第一次出现的那条就是最新的，后面同名的都是旧条。
     */
    private static List<History> dedupe(List<History> items) {
        if (items == null || items.size() < 2) return items;
        Map<String, History> latest = new LinkedHashMap<>();
        List<History> stale = new ArrayList<>();
        for (History item : items) {
            String name = item.getVodName();
            if (TextUtils.isEmpty(name)) continue;
            if (latest.putIfAbsent(name, item) != null) stale.add(item);
        }
        if (stale.isEmpty()) return items;
        List<History> result = new ArrayList<>(items);
        result.removeAll(stale);
        // 删除失败（例如被并发写回）不影响展示，下次进来还会再清一次
        for (History item : stale) {
            try {
                AppDatabase.get().getHistoryDao().delete(item.getCid(), item.getKey());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static History find(String key) {
        return AppDatabase.get().getHistoryDao().find(VodConfig.getCid(), key);
    }

    public static List<History> findByName(String name) {
        try {
            return AppDatabase.get().getHistoryDao().findByName(VodConfig.getCid(), name);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 追更卡片要用哪条观看记录算「已看第几集」。
     *
     * <p>三级兜底，缺一档用户就会看到标签不见：
     * <ol>
     *   <li>cid + key 精确命中（正常情况）；</li>
     *   <li>只按 key 跨配置找——追更列表是跨配置展示的，换过点播源后 cid 会对不上；</li>
     *   <li>同剧名找最新一条——服务端注入、本地下载的 key 与站点记录不同，
     *       但合并之后它们本来就该算同一部剧。</li>
     * </ol>
     */
    public static History forKeep(Keep keep) {
        if (keep == null) return null;
        try {
            History history = AppDatabase.get().getHistoryDao().find(keep.getCid(), keep.getKey());
            if (history != null) return history;
            history = AppDatabase.get().getHistoryDao().findLatest(keep.getKey());
            if (history != null) return history;
            return AppDatabase.get().getHistoryDao().findLatestByVodName(keep.getVodName());
        } catch (Throwable e) {
            return null;
        }
    }

    public static void delete(int cid) {
        AppDatabase.get().getHistoryDao().delete(cid);
    }

    public static void deleteAndSync(int cid) {
        PlaybackProgressWriter.deleteAllFromUser(cid);
    }

    public static void sync(List<History> targets) {
        targets.forEach(target -> {
            if (!target.canMergeByName()) {
                target.cid(VodConfig.getCid()).save();
                return;
            }
            List<History> items = findByName(target.getVodName());
            if (items.isEmpty()) target.cid(VodConfig.getCid()).save();
            else {
                long latestTime = items.stream().mapToLong(History::getCreateTime).max().orElse(0L);
                if (target.getCreateTime() > latestTime) target.cid(VodConfig.getCid()).merge(items, true).save();
            }
        });
    }

    @NonNull
    public String getKey() {
        return key;
    }

    public void setKey(@NonNull String key) {
        this.key = key;
    }

    public String getVodPic() {
        return vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public String getWallPic() {
        return wallPic == null ? "" : wallPic;
    }

    public void setWallPic(String wallPic) {
        this.wallPic = wallPic;
    }

    public String getVodName() {
        return vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodFlag() {
        return vodFlag;
    }

    public void setVodFlag(String vodFlag) {
        this.vodFlag = vodFlag;
    }

    public String getVodRemarks() {
        return vodRemarks == null ? "" : vodRemarks;
    }

    public void setVodRemarks(String vodRemarks) {
        this.vodRemarks = vodRemarks;
    }

    public String getEpisodeUrl() {
        return episodeUrl == null ? "" : episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public boolean isRevSort() {
        return revSort;
    }

    public void setRevSort(boolean revSort) {
        this.revSort = revSort;
    }

    public boolean isRevPlay() {
        return revPlay;
    }

    public void setRevPlay(boolean revPlay) {
        this.revPlay = revPlay;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public long getOpening() {
        return opening;
    }

    public void setOpening(long opening) {
        this.opening = opening;
    }

    public long getEnding() {
        return ending;
    }

    public void setEnding(long ending) {
        this.ending = ending;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getCid() {
        return cid;
    }

    public void setCid(int cid) {
        this.cid = cid;
    }

    public History cid(int cid) {
        setCid(cid);
        return this;
    }

    public String getSiteName() {
        return VodConfig.get().getSite(getSiteKey()).getName();
    }

    public String getSiteKey() {
        return getKey().split(AppDatabase.SYMBOL)[0];
    }

    public String getVodId() {
        String[] parts = Objects.toString(getKey(), "").split(AppDatabase.SYMBOL);
        return parts.length > 1 ? parts[1] : "";
    }

    public Flag getFlag() {
        return Flag.create(getVodFlag());
    }

    public Episode getEpisode() {
        return Episode.create(getVodRemarks(), getEpisodeUrl());
    }

    public int getSiteVisible() {
        return TextUtils.isEmpty(getSiteName()) ? View.GONE : View.VISIBLE;
    }

    public int getRevPlayText() {
        return isRevPlay() ? R.string.play_backward : R.string.play_forward;
    }

    public int getRevPlayHint() {
        return isRevPlay() ? R.string.play_backward_hint : R.string.play_forward_hint;
    }

    private boolean canMergeByName() {
        // 以前这里把 push（服务端注入 / 本地下载）排除在按剧名合并之外，理由是它的 key 是
        // push@@@<地址>，和站点记录长得不一样。结果是同一部剧在「最近观看」里出现好几条：
        // 一条站点的、一条注入的、一条本地下好的，用户明确要求合并成一条只留最新那一集。
        // 合并条件本身还有「时长差不超过 10 分钟」兜底，不会把两部同名不同剧拼到一起。
        return true;
    }

    private boolean shouldMerge(History item, boolean force) {
        if (!canMergeByName() || !item.canMergeByName()) return false;
        if (!force && getKey().equals(item.getKey())) return false;
        if (getDuration() <= 0 || item.getDuration() <= 0) return true;
        return Math.abs(getDuration() - item.getDuration()) <= TimeUnit.MINUTES.toMillis(10);
    }

    private History copyTo(History item) {
        if (getOpening() > 0) item.setOpening(getOpening());
        if (getEnding() > 0) item.setEnding(getEnding());
        if (getSpeed() != 1) item.setSpeed(getSpeed());
        return this;
    }

    public boolean canSave() {
        return getPosition() > 0;
    }

    public boolean isNearEnding() {
        if (getPosition() <= 0 || getDuration() <= 0) return false;
        long threshold = Math.min(NEAR_END_MAX_MS, Math.max(NEAR_END_MIN_MS, getDuration() / 100));
        long remaining = getDuration() - getPosition();
        return remaining >= 0 && remaining <= threshold;
    }

    /** 进度已到达或超过时长（整剧共用进度时常见于切集），这种进度不能用于 seek，否则播放器会立刻进入 ENDED。 */
    public boolean isPositionOvershoot(long duration) {
        return getPosition() > 0 && duration > 0 && getPosition() >= duration;
    }

    public void resetPlaybackPosition() {
        setPosition(C.TIME_UNSET);
        setDuration(C.TIME_UNSET);
    }

    public boolean canSync() {
        return System.currentTimeMillis() - getUpdateTime() > 5000;
    }

    public History merge() {
        merge(false);
        return this;
    }

    private History merge(boolean force) {
        if (!canMergeByName()) return this;
        return merge(findByName(getVodName()), force);
    }

    private History merge(List<History> items, boolean force) {
        for (History item : items) if (item.shouldMerge(this, force)) item.copyTo(this).delete();
        return this;
    }

    public void replace(String key) {
        delete();
        setKey(key);
    }

    public History save(int cid) {
        return cid(cid).merge(true).save();
    }

    public History save() {
        updateTime = System.currentTimeMillis();
        AppDatabase.get().getHistoryDao().insertOrUpdate(this);
        com.fongmi.android.tv.setting.SyncScheduler.onDataChanged("history");
        return this;
    }

    public History delete() {
        AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), getKey());
        AppDatabase.get().getTrackDao().delete(getKey());
        com.fongmi.android.tv.setting.SyncScheduler.onDataChanged("history-delete");
        return this;
    }

    public History deleteAndSync() {
        PlaybackProgressWriter.deleteFromUser(this);
        return this;
    }

    public void findEpisode(List<Flag> flags) {
        if (flags.isEmpty()) return;
        setVodFlag(flags.get(0).getFlag());
        if (!flags.get(0).getEpisodes().isEmpty()) {
            Episode episode = flags.get(0).getEpisodes().get(0);
            setVodRemarks(episode.getName());
            setEpisodeUrl(episode.getUrl());
        }
        if (!canMergeByName()) return;
        for (History item : findByName(getVodName())) {
            if (getPosition() > 0) break;
            for (Flag flag : flags) {
                Episode episode = flag.find(item.getEpisode(), true);
                if (episode == null) continue;
                item.copyTo(this);
                setVodFlag(flag.getFlag());
                setPosition(item.getPosition());
                setVodRemarks(episode.getName());
                setEpisodeUrl(episode.getUrl());
                break;
            }
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof History it)) return false;
        return Objects.equals(getKey(), it.getKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getKey());
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean isSameItem(History other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(History other) {
        return getVodName().equals(other.getVodName()) && getVodPic().equals(other.getVodPic()) && getWallPic().equals(other.getWallPic())
                && getCreateTime() == other.getCreateTime() && getPosition() == other.getPosition()
                && getDuration() == other.getDuration() && getVodRemarks().equals(other.getVodRemarks());
    }
}
