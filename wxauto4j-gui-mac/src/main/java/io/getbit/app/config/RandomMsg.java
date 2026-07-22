package io.getbit.app.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 随机定时消息任务配置
 *
 * <p>对标 SiverWXbot_plus 的 random_msg_list 条目。
 * 在设定的时间窗口内随机挑选时刻发送消息。</p>
 */
public class RandomMsg {

    /** 任务唯一 ID */
    private String id = "";

    /** 是否启用 */
    private boolean enabled = true;

    /** 发送目标列表 */
    private List<String> targets = new ArrayList<>();

    /** 时间窗口开始（HH:MM） */
    private String timeStart = "09:00";

    /** 时间窗口结束（HH:MM） */
    private String timeEnd = "21:00";

    /** 重复类型：daily / weekly / monthly */
    private String repeatType = "daily";

    /** 每周/每月随机抽取的发送天数 */
    private int randomDaysCount = 1;

    /** 消息内容列表（文字或图片绝对路径） */
    private List<String> msgs = new ArrayList<>();

    public RandomMsg() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }

    public String getTimeStart() { return timeStart; }
    public void setTimeStart(String timeStart) { this.timeStart = timeStart; }

    public String getTimeEnd() { return timeEnd; }
    public void setTimeEnd(String timeEnd) { this.timeEnd = timeEnd; }

    public String getRepeatType() { return repeatType; }
    public void setRepeatType(String repeatType) { this.repeatType = repeatType; }

    public int getRandomDaysCount() { return randomDaysCount; }
    public void setRandomDaysCount(int randomDaysCount) { this.randomDaysCount = randomDaysCount; }

    public List<String> getMsgs() { return msgs; }
    public void setMsgs(List<String> msgs) { this.msgs = msgs; }
}
