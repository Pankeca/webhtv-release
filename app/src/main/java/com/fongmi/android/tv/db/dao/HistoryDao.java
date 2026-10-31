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
