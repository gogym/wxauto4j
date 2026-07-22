package io.getbit.app.bot;

import io.getbit.MomentsWnd;
import io.getbit.WeChat;
import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ScheduledMoment;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 朋友圈自动处理器
 *
 * <p>功能：</p>
 * <ul>
 *   <li>随机点赞（活跃账号）</li>
 *   <li>定时发布朋友圈</li>
 *   <li>随机时间发布朋友圈</li>
 *   <li>支持图文混发、隐私控制</li>
 * </ul>
 */
public class MomentsHandler {

    private static final Logger LOG = Logger.getLogger(MomentsHandler.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppConfig config;
    private final WeChat weChat;
    private final Random random = new Random();

    private ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> tasks = new java.util.ArrayList<>();

    /** 上次点赞时间 */
    private long lastLikeTime = 0;

    public MomentsHandler(AppConfig config, WeChat weChat) {
        this.config = config;
        this.weChat = weChat;
    }

    /**
     * 启动朋友圈任务
     */
    public void start() {
        if (executor != null) {
            executor.shutdown();
        }
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "moments-handler");
            t.setDaemon(true);
            return t;
        });
        tasks.clear();

        // 启动随机点赞
        if (config.isMomentsLikeSwitch()) {
            scheduleLikeTask();
        }

        // 启动定时朋友圈
        if (config.isScheduledMomentsSwitch()) {
            for (ScheduledMoment moment : config.getScheduledMomentsList()) {
                if (moment.isEnabled()) {
                    scheduleMomentTask(moment);
                }
            }
        }

        // 启动随机朋友圈
        if (config.isRandomMomentsSwitch()) {
            for (ScheduledMoment moment : config.getRandomMomentsList()) {
                if (moment.isEnabled()) {
                    scheduleRandomMomentTask(moment);
                }
            }
        }

        LOG.info("朋友圈处理器已启动");
    }

    /**
     * 停止朋友圈任务
     */
    public void stop() {
        for (ScheduledFuture<?> task : tasks) {
            task.cancel(false);
        }
        tasks.clear();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * 调度随机点赞任务
     */
    private void scheduleLikeTask() {
        int minMinutes = Math.max(1, config.getMomentsLikeMin());
        int maxMinutes = Math.max(minMinutes, config.getMomentsLikeMax());
        int delay = minMinutes + random.nextInt(maxMinutes - minMinutes);

        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                doRandomLike();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "随机点赞失败", e);
            }
            // 重新调度
            scheduleLikeTask();
        }, delay, TimeUnit.MINUTES);

        tasks.add(future);
        LOG.info("下次随机点赞延迟: " + delay + " 分钟");
    }

    /**
     * 执行随机点赞
     */
    private void doRandomLike() {
        try {
            MomentsWnd momentsWnd = weChat.Moments();
            if (momentsWnd == null) {
                LOG.warning("无法打开朋友圈");
                return;
            }

            List<MomentsWnd.Moment> moments = momentsWnd.GetMoments();
            if (!moments.isEmpty()) {
                // 对第一条朋友圈点赞
                MomentsWnd.Moment first = moments.get(0);
                first.Like(true);
                LOG.info("已对朋友圈点赞");

                // 拟人化延迟
                Thread.sleep(1000 + random.nextInt(4000));
            }

            momentsWnd.Close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "朋友圈点赞操作失败", e);
        }
    }

    /**
     * 调度定时朋友圈任务
     */
    private void scheduleMomentTask(ScheduledMoment moment) {
        long delay = calculateDelay(moment.getTime());

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                publishMoment(moment);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "定时朋友圈发布失败: " + moment.getId(), e);
            }
        }, delay, 24 * 60 * 60, TimeUnit.SECONDS); // 每天重复

        tasks.add(future);
        LOG.info("已调度定时朋友圈: " + moment.getId() + " 时间: " + moment.getTime());
    }

    /**
     * 调度随机朋友圈任务
     */
    private void scheduleRandomMomentTask(ScheduledMoment moment) {
        long delay = calculateRandomDelay(moment.getTimeStart(), moment.getTimeEnd());

        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                publishMoment(moment);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "随机朋友圈发布失败: " + moment.getId(), e);
            }
            // 重新调度
            scheduleRandomMomentTask(moment);
        }, delay, TimeUnit.SECONDS);

        tasks.add(future);
        LOG.info("已调度随机朋友圈: " + moment.getId());
    }

    /**
     * 发布朋友圈
     */
    private void publishMoment(ScheduledMoment moment) {
        try {
            String text = moment.getText();
            List<String> images = moment.getImages();

            // 构建隐私配置
            Map<String, Object> privacyConfig = null;
            if (!"public".equals(moment.getPrivacy())) {
                privacyConfig = new HashMap<>();
                privacyConfig.put("type", moment.getPrivacy());
                privacyConfig.put("tags", moment.getTags());
            }

            weChat.PublishMoment(text, images, privacyConfig);
            LOG.info("朋友圈已发布: " + moment.getId());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "发布朋友圈失败", e);
        }
    }

    /**
     * 计算到指定时间的延迟（秒）
     */
    private long calculateDelay(String time) {
        LocalTime target = LocalTime.parse(time, TIME_FMT);
        LocalTime now = LocalTime.now();
        long seconds = target.toSecondOfDay() - now.toSecondOfDay();
        if (seconds <= 0) {
            seconds += 24 * 60 * 60; // 推到明天
        }
        return seconds;
    }

    /**
     * 计算随机延迟（秒）
     */
    private long calculateRandomDelay(String timeStart, String timeEnd) {
        LocalTime start = LocalTime.parse(timeStart, TIME_FMT);
        LocalTime end = LocalTime.parse(timeEnd, TIME_FMT);
        LocalTime now = LocalTime.now();

        long startSec = start.toSecondOfDay();
        long endSec = end.toSecondOfDay();
        if (endSec <= startSec) endSec += 24 * 60 * 60;

        long randomSec = startSec + random.nextInt((int) (endSec - startSec));
        long nowSec = now.toSecondOfDay();
        long delay = randomSec - nowSec;
        if (delay < 0) delay += 24 * 60 * 60;

        return delay;
    }
}
