package org.jeecg.modules.szr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.szr.entity.TabSzrDz;
import org.jeecg.modules.demo.szr.service.ITabSzrDzService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数字人动作 -> 驱动形象 的解析。
 *
 * <p>业务侧只认动作主键（tab_szr_dz.id，如「小张-左介绍」），
 * 驱动服务只认形象 id（avatar_id，如 {@code zhang_left}）。这里做这层翻译。
 *
 * <p><b>形象 id 是什么</b>：驱动服务上的一个预处理缓存目录
 * {@code <MuseTalk根目录>/results/<版本>/avatars/<avatar_id>/}。
 * 每个要说话的动作视频都得单独跑一次 avatar 预处理才会产出它 ——
 * MuseTalk 说话时的画面底板只来自这个缓存，不会去读原始视频文件。
 *
 * <p>⚠ 解析不到就返回 null，让驱动服务用它的默认形象。
 * 宁可用错形象也不能不出声 —— 配置问题不该表现成"点了没反应"。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrAvatarService {

    /** 动作类型：静置待机，不说话时循环播放，播报时用不到 */
    public static final int TYPE_IDLE = 0;
    /** 动作类型：说话驱动 */
    public static final int TYPE_SPEAK = 1;

    @Autowired
    private ITabSzrDzService tabSzrDzService;

    /**
     * 按动作主键取驱动形象 id。
     *
     * @param dzId tab_szr_dz 主键，可为空
     * @return 形象 id；取不到返回 null（由驱动服务回落到默认形象）
     */
    public String resolveByDzId(String dzId) {
        if (dzId == null || dzId.trim().isEmpty()) {
            return null;
        }
        TabSzrDz dz = tabSzrDzService.getById(dzId.trim());
        if (dz == null) {
            log.warn("[szr] 动作 {} 不存在，用驱动服务默认形象", dzId);
            return null;
        }
        // 静置动作是给"不说话"用的，拿它驱动口型没有意义。
        // 这里只告警不拦截：真配错了也让它照默认形象播出去，别把播报堵死。
        if (dz.getDzType() != null && dz.getDzType() == TYPE_IDLE) {
            log.warn("[szr] 动作「{}」是静置待机类型，不能用来说话，改用默认形象", dz.getSzrTitle());
            return null;
        }
        String avatarId = dz.getAvatarId();
        if (avatarId == null || avatarId.trim().isEmpty()) {
            log.warn("[szr] 动作「{}」没填驱动形象ID，用驱动服务默认形象。"
                    + "请在「数字人动作管理」里补上，并确认驱动服务上已有对应的预处理目录",
                    dz.getSzrTitle());
            return null;
        }
        return avatarId.trim();
    }

    /**
     * 某个数字人的默认说话动作对应的形象 id。
     *
     * <p>播报接口没指定动作时走这里。同一个数字人下配了多条默认时取第一条。
     */
    public String resolveDefault(String szrId) {
        if (szrId == null || szrId.trim().isEmpty()) {
            return null;
        }
        QueryWrapper<TabSzrDz> w = new QueryWrapper<>();
        w.eq("szr_id", szrId.trim());
        w.eq("dz_type", TYPE_SPEAK);
        w.eq("is_default", 1);
        w.orderByAsc("create_time");
        List<TabSzrDz> list = tabSzrDzService.list(w);
        for (TabSzrDz dz : list) {
            String avatarId = dz.getAvatarId();
            if (avatarId != null && !avatarId.trim().isEmpty()) {
                return avatarId.trim();
            }
        }
        return null;
    }

    /** 某个数字人可用于说话的动作列表；szrId 为空则返回全部。 */
    public List<TabSzrDz> listSpeakActions(String szrId) {
        QueryWrapper<TabSzrDz> w = new QueryWrapper<>();
        if (szrId != null && !szrId.trim().isEmpty()) {
            w.eq("szr_id", szrId.trim());
        }
        w.eq("dz_type", TYPE_SPEAK);
        w.orderByDesc("is_default").orderByAsc("create_time");
        return tabSzrDzService.list(w);
    }
}
