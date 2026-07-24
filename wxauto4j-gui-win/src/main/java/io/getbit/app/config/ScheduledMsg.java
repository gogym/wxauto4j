package io.getbit.app.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时消息任务配置
 *
 * <p>对标 SiverWXbot_plus 的 scheduled_msg_list 条目。</p>
 */
public class ScheduledMsg {

    /** 任务唯一 ID */
    private String id = "";

    /** 是否启用 */
    private boolean enabled = true;

    /** 发送目标列表（用户/群聊名称） */
    private List<String> targets = new ArrayList<>();

    /** 发送时间（HH:MM） */
    private String time = "08:00";

    /** 重复类型：once / daily / weekly / monthly / custom */
    private String repeatType = "daily";

    /** weekly 时使用：1=周一…7=周日 */
    private List<Integer> weekdays = new ArrayList<>();

    /** monthly/custom 时使用：日期列表 */
    private List<String> dates = new ArrayList<>();

    /** 消息内容列表（文字或图片绝对路径） */
    private List<String> msgs = new ArrayList<>();

    public ScheduledMsg() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getRepeatType() { return repeatType; }
    public void setRepeatType(String repeatType) { this.repeatType = repeatType; }

    public List<Integer> getWeekdays() { return weekdays; }
    public void setWeekdays(List<Integer> weekdays) { this.weekdays = weekdays; }

    public List<String> getDates() { return dates; }
    public void setDates(List<String> dates) { this.dates = dates; }

    public List<String> getMsgs() { return msgs; }
    public void setMsgs(List<String> msgs) { this.msgs = msgs; }
}
