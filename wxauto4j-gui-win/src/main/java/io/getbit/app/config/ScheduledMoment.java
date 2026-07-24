package io.getbit.app.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时/随机朋友圈任务配置
 *
 * <p>对标 SiverWXbot_plus 的 scheduled_moments_list 和 random_moments_list 条目。</p>
 */
public class ScheduledMoment {

    /** 任务唯一 ID */
    private String id = "";

    /** 是否启用 */
    private boolean enabled = true;

    /** 发布时间（HH:MM），定时模式使用 */
    private String time = "12:00";

    /** 时间窗口开始（HH:MM），随机模式使用 */
    private String timeStart = "09:00";

    /** 时间窗口结束（HH:MM），随机模式使用 */
    private String timeEnd = "21:00";

    /** 重复类型：once / daily / weekly / monthly / custom */
    private String repeatType = "daily";

    /** weekly 时使用：1=周一…7=周日 */
    private List<Integer> weekdays = new ArrayList<>();

    /** monthly/custom 时使用：日期列表 */
    private List<String> dates = new ArrayList<>();

    /** 每周/每月随机抽取的天数（随机模式） */
    private int randomDaysCount = 1;

    /** 朋友圈文字内容 */
    private String text = "";

    /** 本地图片绝对路径列表，最多 9 张 */
    private List<String> images = new ArrayList<>();

    /** 隐私设置：public / whitelist / blacklist */
    private String privacy = "public";

    /** 隐私标签列表 */
    private List<String> tags = new ArrayList<>();

    public ScheduledMoment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getTimeStart() { return timeStart; }
    public void setTimeStart(String timeStart) { this.timeStart = timeStart; }

    public String getTimeEnd() { return timeEnd; }
    public void setTimeEnd(String timeEnd) { this.timeEnd = timeEnd; }

    public String getRepeatType() { return repeatType; }
    public void setRepeatType(String repeatType) { this.repeatType = repeatType; }

    public List<Integer> getWeekdays() { return weekdays; }
    public void setWeekdays(List<Integer> weekdays) { this.weekdays = weekdays; }

    public List<String> getDates() { return dates; }
    public void setDates(List<String> dates) { this.dates = dates; }

    public int getRandomDaysCount() { return randomDaysCount; }
    public void setRandomDaysCount(int randomDaysCount) { this.randomDaysCount = randomDaysCount; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public String getPrivacy() { return privacy; }
    public void setPrivacy(String privacy) { this.privacy = privacy; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
