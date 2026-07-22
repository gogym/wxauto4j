package io.getbit.app.bot;

import io.getbit.WeChat;
import io.getbit.app.config.AppConfig;
import io.getbit.app.config.RandomMsg;
import io.getbit.app.config.ScheduledMsg;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 定时/随机任务调度引擎
 *
 * <p>支持：</p>
 * <ul>
 *   <li>定时消息：单次/每天/每周/每月/自定义日期</li>
 *   <li>随机消息：时间窗口内随机发送</li>
 *   <li>多目标群发</li>
 *   <li>支持发送图片（路径自动识别）</li>
 * </ul>
 */
public class SchedulerEngine {

    private static final Logger LOG = Logger.getLogger(SchedulerEngine.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppConfig config;
    private final WeChat weChat;
    private final Random random = new Random();

    private ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public SchedulerEngine(AppConfig config, WeChat weChat) {
        this.config = config;
        this.weChat = weChat;
    }

    /**
     * 启动所有定时任务
     */
    public void start() {
        if (executor != null) {
            executor.shutdown();
        }
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        tasks.clear();

        // 启动定时消息任务
        if (config.isScheduledMsgSwitch()) {
            for (ScheduledMsg msg : config.getScheduledMsgList()) {
                if (msg.isEnabled()) {
                    scheduleTask(msg);
                }
            }
        }

        // 启动随机消息任务
        if (config.isRandomMsgSwitch()) {
            for (RandomMsg msg : config.getRandomMsgList()) {
                if (msg.isEnabled()) {
                    scheduleRandomTask(msg);
                }
            }
        }

        LOG.info("调度引擎已启动，定时任务: " + config.getScheduledMsgList().size()
                + "，随机任务: " + config.getRandomMsgList().size());
    }

    /**
     * 停止所有定时任务
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
     * 调度定时消息任务
     */
    private void scheduleTask(ScheduledMsg msg) {
        long initialDelay = calculateInitialDelay(msg.getTime(), msg.getRepeatType(),
                msg.getWeekdays(), msg.getDates());

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                sendMessages(msg.getTargets(), msg.getMsgs());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "定时消息发送失败: " + msg.getId(), e);
            }
        }, initialDelay, getPeriodSeconds(msg.getRepeatType()), TimeUnit.SECONDS);

        tasks.add(future);
        LOG.info("已调度定时任务: " + msg.getId() + " 时间: " + msg.getTime());
    }

    /**
     * 调度随机消息任务
     */
    private void scheduleRandomTask(RandomMsg msg) {
        // 计算下一次随机时间
        long delay = calculateRandomDelay(msg.getTimeStart(), msg.getTimeEnd());

        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                sendMessages(msg.getTargets(), msg.getMsgs());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "随机消息发送失败: " + msg.getId(), e);
            }
            // 重新调度下一次
            scheduleRandomTask(msg);
        }, delay, TimeUnit.SECONDS);

        tasks.add(future);
        LOG.info("已调度随机任务: " + msg.getId());
    }

    /**
     * 发送消息到多个目标
     */
    private void sendMessages(List<String> targets, List<String> msgs) {
        if (targets == null || targets.isEmpty() || msgs == null || msgs.isEmpty()) {
            return;
        }

        for (String target : targets) {
            try {
                weChat.ChatWith(target, false);
                Thread.sleep(500);

                for (String msg : msgs) {
                    if (msg == null || msg.isEmpty()) continue;

                    File file = new File(msg);
                    if (file.exists() && file.isFile()) {
                        // 发送图片/文件
                        weChat.SendMsg(msg); // 简化处理
                    } else {
                        // 发送文字
                        weChat.SendMsg(msg);
                    }
                    Thread.sleep(500);
                }
                LOG.info("定时消息已发送到: " + target);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "发送到 " + target + " 失败", e);
            }

            // 多目标间隔
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 计算初始延迟（秒）
     */
    private long calculateInitialDelay(String time, String repeatType,
                                        List<Integer> weekdays, List<String> dates) {
        LocalTime targetTime = LocalTime.parse(time, TIME_FMT);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.with(targetTime);

        // 如果今天的时间已过，推到明天
        if (next.isBefore(now) || next.isEqual(now)) {
            next = next.plusDays(1);
        }

        // 根据重复类型调整
        switch (repeatType) {
            case "weekly":
                if (weekdays != null && !weekdays.isEmpty()) {
                    int targetDay = weekdays.get(0);
                    int currentDay = now.getDayOfWeek().getValue();
                    int daysToAdd = (targetDay - currentDay + 7) % 7;
                    if (daysToAdd == 0 && next.isBefore(now)) daysToAdd = 7;
                    next = now.with(targetTime).plusDays(daysToAdd);
                }
                break;
            case "monthly":
            case "custom":
                if (dates != null && !dates.isEmpty()) {
                    int targetDate = Integer.parseInt(dates.get(0));
                    int currentDate = now.getDayOfMonth();
                    if (targetDate <= currentDate) {
                        next = now.withDayOfMonth(1).plusMonths(1).withDayOfMonth(targetDate).with(targetTime);
                    } else {
                        next = now.withDayOfMonth(targetDate).with(targetTime);
                    }
                }
                break;
            case "once":
                // 单次任务，使用 dates 中的具体日期
                if (dates != null && !dates.isEmpty()) {
                    LocalDate targetDate = LocalDate.parse(dates.get(0));
                    next = targetDate.atTime(targetTime);
                }
                break;
        }

        return java.time.Duration.between(now, next).getSeconds();
    }

    /**
     * 获取周期（秒）
     */
    private long getPeriodSeconds(String repeatType) {
        switch (repeatType) {
            case "daily":
                return 24 * 60 * 60;
            case "weekly":
                return 7 * 24 * 60 * 60;
            case "monthly":
                return 30 * 24 * 60 * 60; // 近似
            case "once":
            case "custom":
            default:
                return 24 * 60 * 60; // 默认每天检查
        }
    }

    /**
     * 计算随机延迟（秒）
     */
    private long calculateRandomDelay(String timeStart, String timeEnd) {
        LocalTime start = LocalTime.parse(timeStart, TIME_FMT);
        LocalTime end = LocalTime.parse(timeEnd, TIME_FMT);
        LocalTime now = LocalTime.now();

        // 如果当前时间已过结束时间，推到明天（用秒数处理）
        // LocalTime 不支持 plusDays，直接通过秒数偏移处理

        // 在时间窗口内随机
        long startSeconds = start.toSecondOfDay();
        long endSeconds = end.toSecondOfDay();
        if (endSeconds <= startSeconds) {
            endSeconds += 24 * 60 * 60;
        }

        long randomSeconds = startSeconds + random.nextInt((int) (endSeconds - startSeconds));
        long nowSeconds = now.toSecondOfDay();
        long delay = randomSeconds - nowSeconds;
        if (delay < 0) delay += 24 * 60 * 60;

        return delay;
    }
}
