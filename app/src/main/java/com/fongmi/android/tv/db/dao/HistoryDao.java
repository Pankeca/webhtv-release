package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.History;

import java.util.List;

@Dao
public abstract class HistoryDao extends BaseDao<History> {

    @Query("SELECT * FROM History")
    public abstract List<History> findAll();

    @Query("SELECT * FROM History WHERE cid = :cid")
    public abstract List<History> findAll(int cid);

    @Query("SELECT * FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    public abstract List<History> find(int cid, long createTime);

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` = :key")
    public abstract History find(int cid, String key);

    /**
     * 不区分配置找同一条记录。
     *
     * <p>用途：追更列表是跨配置展示的（{@link com.fongmi.android.tv.bean.Keep#getVod()} 不带 cid 过滤），
     * 用户换过点播源之后 Keep 里的 cid 和 History 里的 cid 就可能对不上，
     * 一按 cid 精确查就查不到，追更卡片上的「已看第几集」就消失了。
     */
    @Query("SELECT * FROM History WHERE `key` = :key ORDER BY createTime DESC LIMIT 1")
    public abstract History findLatest(String key);

    /** 按剧名找最新一条：注入/下载记录的 key 与站点不同，合并后只能靠剧名认同同一部剧。 */
    @Query("SELECT * FROM History WHERE vodName = :vodName ORDER BY createTime DESC LIMIT 1")
    public abstract History findLatestByVodName(String vodName);

    /**
     * 按剧名前缀找最新一条，取长度最接近的那条。
     *
     * <p>不少站点把别名/外文名拼在剧名后面（「超巡！超条前辈 超巡！超条先輩」），
     * 追更里存的却是干净的名字，精确比对永远落空；而不同站点同一部剧的影片 id 也不一样，
     * 后面几级兜底也救不回来。这里用前缀召回，再按「长度差最小」挑最贴近的那条，
     * 避免「斗罗大陆」误吸「斗罗大陆2 绝世唐门」这种续作。
     */
    @Query("SELECT * FROM History WHERE vodName LIKE :prefix || '%' ORDER BY ABS(LENGTH(vodName) - LENGTH(:prefix)) ASC, createTime DESC LIMIT 1")
    public abstract History findLatestByVodNamePrefix(String prefix);

    /**
     * 反向的前缀匹配：记录里的剧名比给定名字更短。
     *
     * <p>反过来也有：下载/追更用的是「我在修仙界开超市第一季」，观看记录里却是「我在修仙界开超市」。
     * 同样按长度差挑最贴近的一条，避免把「斗罗大陆」当成「斗罗大陆2 绝世唐门」。
     */
    @Query("SELECT * FROM History WHERE :vodName LIKE vodName || '%' ORDER BY ABS(LENGTH(vodName) - LENGTH(:vodName)) ASC, createTime DESC LIMIT 1")
    public abstract History findLatestByVodNameCovering(String vodName);

    /**
     * 剧名互相包含（前后缀都不对齐时的最后一档）。
     *
     * <p>例：追更/下载存的是「我在修仙界开超市第一季」，历史里是「修仙界开超市（第一季）」这种
     * 中间夹了标点或副标题的写法，前后缀都对不上。同样按长度差挑最贴近的一条。
     * 名字太短（少于 3 个字）时不要用，容易认错。
     */
    @Query("SELECT * FROM History WHERE vodName LIKE '%' || :name || '%' ORDER BY ABS(LENGTH(vodName) - LENGTH(:name)) ASC, createTime DESC LIMIT 1")
    public abstract History findLatestByVodNameContains(String name);
    /**
     * 按「站点内影片 id」找最新一条（key = 站点@影片 id）。
     *
     * <p>追更里的动漫常常和最近观看对不上：剧名一份带季数、一份不带，或者换了点播源导致 cid 不同，
     * 前三级（cid+key / key / 剧名）全部落空，卡片上就没有「已看到第几集」。
     * 同一个站点里影片 id 是稳定的，用 key 的 @id 后缀兜住这一档。
     */
    @Query("SELECT * FROM History WHERE `key` LIKE '%' || :suffix ORDER BY createTime DESC LIMIT 1")
    public abstract History findLatestByKeySuffix(String suffix);

    @Query("SELECT * FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC")
    public abstract List<History> findByName(int cid, String vodName);

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` LIKE :keyPrefix || '%' ORDER BY createTime DESC")
    public abstract List<History> findByKeyPrefix(int cid, String keyPrefix);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    public abstract int delete(int cid, String key);

    @Query("DELETE FROM History WHERE cid = :cid AND `key` LIKE :keyPrefix || '%'")
    public abstract int deleteByKeyPrefix(int cid, String keyPrefix);

    @Query("DELETE FROM History WHERE cid = :cid")
    public abstract int delete(int cid);

    @Query("DELETE FROM History")
    public abstract int delete();
}
