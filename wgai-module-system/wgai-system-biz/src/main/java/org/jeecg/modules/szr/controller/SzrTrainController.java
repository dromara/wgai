package org.jeecg.modules.szr.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.szr.service.SzrTrainConfig;
import org.jeecg.modules.szr.service.SzrTrainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 数字人形象训练 / 启用。
 *
 * <p>⚠ 要求 Java 和驱动服务在同一台服务器上 —— 这里是直接读写本地文件、执行本地命令的。
 *
 * @author wggg
 */
@Slf4j
@Api(tags = "数字人训练")
@RestController
@RequestMapping("/szr/train")
public class SzrTrainController {

    @Autowired
    private SzrTrainService trainService;

    @Autowired
    private SzrTrainConfig trainConfig;

    /**
     * 训练一个说话动作（跑一次 MuseTalk avatar 预处理）。
     *
     * <p>异步执行，返回任务 id，用 {@code /szr/train/task} 轮询进度。
     */
    @ApiOperation(value = "训练动作形象", notes = "对该动作的视频跑一次预处理")
    @PostMapping("/action/{dzId}")
    public Result<String> trainAction(@PathVariable("dzId") String dzId) {
        try {
            return Result.OK(trainService.submitTrain(dzId));
        } catch (Exception e) {
            log.warn("[szr-train] 提交训练失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 训练【这一个数字人】名下的全部说话动作（站立 / 左介绍 / 右介绍…）。
     *
     * <p>作用域是 szrId 这一个人，不是全库所有数字人。
     * 串行跑，一个动作失败不影响后面的。静置动作不参与 —— 它只是循环播放的素材，
     * 不做口型驱动，不需要预处理。
     */
    @ApiOperation(value = "训练该数字人的全部动作", notes = "只训练这一个人名下的动作")
    @PostMapping("/szr/{szrId}")
    public Result<String> trainSzr(@PathVariable("szrId") String szrId) {
        try {
            return Result.OK(trainService.submitTrainSzr(szrId));
        } catch (Exception e) {
            log.warn("[szr-train] 提交训练失败(数字人 {}): {}", szrId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 启用某个数字人：把它的静置画面和说话形象写进驱动服务配置，然后重启驱动服务。
     *
     * <p>⚠ 会让视频流中断 10~30 秒，前端需要重连。
     * 同一时刻只有一个数字人处于启用状态。
     */
    @ApiOperation(value = "启用数字人", notes = "改驱动服务配置并重启，流会中断")
    @PostMapping("/enable/{szrId}")
    public Result<String> enable(@PathVariable("szrId") String szrId) {
        try {
            return Result.OK(trainService.submitEnable(szrId));
        } catch (Exception e) {
            log.warn("[szr-train] 启用失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /** 最近一次任务的进度和日志，前端轮询用 */
    @GetMapping("/task")
    public Result<Map<String, Object>> task() {
        SzrTrainService.TrainTask t = trainService.getLastTask();
        Map<String, Object> ret = new HashMap<>();
        ret.put("busy", trainService.isBusy());
        if (t == null) {
            ret.put("state", "idle");
            return Result.OK(ret);
        }
        ret.put("id", t.getId());
        ret.put("type", t.getType());
        ret.put("target", t.getTarget());
        ret.put("state", t.getState());
        ret.put("message", t.getMessage());
        ret.put("startAt", t.getStartAt());
        ret.put("endAt", t.getEndAt());
        ret.put("logs", t.snapshotLogs());
        return Result.OK(ret);
    }

    /**
     * 训练环境自检。
     *
     * <p>配置页填完先点这个，比直接点训练再看报错快得多。
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> check() {
        Map<String, Object> ret = new HashMap<>();
        try {
            trainConfig.ensureReady();
            ret.put("ok", true);
            ret.put("museRoot", trainConfig.getMuseRoot());
            ret.put("version", trainConfig.getVersion());
            ret.put("fps", trainConfig.getFps());
            ret.put("venv", trainConfig.getVenv());
            ret.put("conf", trainConfig.getConfPath());
            ret.put("streamConf", trainConfig.getStreamConfPath());
            ret.put("restart", trainConfig.getRestartPath());
            ret.put("busy", trainService.isBusy());
            return Result.OK(ret);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /** 某个形象的缓存是否已经存在（列表页显示"已训练"用） */
    @GetMapping("/cache/{avatarId}")
    public Result<Map<String, Object>> cache(@PathVariable("avatarId") String avatarId) {
        Map<String, Object> ret = new HashMap<>();
        try {
            trainConfig.ensureReady();
            if (!SzrTrainConfig.SAFE_ID.matcher(avatarId).matches()) {
                return Result.error("驱动形象ID不合法，只能用字母、数字、下划线、连字符");
            }
            ret.put("exists", trainConfig.cacheComplete(avatarId));
            ret.put("frames", trainConfig.frameCount(avatarId));
            ret.put("dir", trainConfig.avatarDir(avatarId).getAbsolutePath());
            return Result.OK(ret);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
