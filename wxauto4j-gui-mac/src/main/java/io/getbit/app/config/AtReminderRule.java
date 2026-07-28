package io.getbit.app.config;

/**
 * @提醒未回复追踪规则
 *
 * <p>当源群中有人@目标人时，启动计时器。
 * 如果目标人在超时时间内未在源群发言，则在目标群发送提醒消息。</p>
 */
public class AtReminderRule {

    /** 规则名称（用于 GUI 显示） */
    private String name = "";

    /** 监控的源群 username（如 xxx@chatroom） */
    private String sourceGroup = "";

    /** 发送提醒的目标群 username */
    private String targetGroup = "";

    /** 被@的目标人显示名称（昵称） */
    private String targetPerson = "";

    /** 超时时间（分钟） */
    private int timeoutMinutes = 10;

    /** 提醒消息模板，支持变量：{person} {message} {sourceGroup} */
    private String reminderTemplate = "⏰ 提醒：{person} 在「{sourceGroup}」被@了，已过{timeout}分钟未回复，原始消息：{message}";

    /** 是否启用 */
    private boolean enabled = true;

    public AtReminderRule() {}

    public AtReminderRule(String name, String sourceGroup, String targetGroup,
                          String targetPerson, int timeoutMinutes, String reminderTemplate) {
        this.name = name;
        this.sourceGroup = sourceGroup;
        this.targetGroup = targetGroup;
        this.targetPerson = targetPerson;
        this.timeoutMinutes = timeoutMinutes;
        this.reminderTemplate = reminderTemplate;
    }

    // ===== Getter / Setter =====

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSourceGroup() { return sourceGroup; }
    public void setSourceGroup(String sourceGroup) { this.sourceGroup = sourceGroup; }

    public String getTargetGroup() { return targetGroup; }
    public void setTargetGroup(String targetGroup) { this.targetGroup = targetGroup; }

    public String getTargetPerson() { return targetPerson; }
    public void setTargetPerson(String targetPerson) { this.targetPerson = targetPerson; }

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    public String getReminderTemplate() { return reminderTemplate; }
    public void setReminderTemplate(String reminderTemplate) { this.reminderTemplate = reminderTemplate; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
