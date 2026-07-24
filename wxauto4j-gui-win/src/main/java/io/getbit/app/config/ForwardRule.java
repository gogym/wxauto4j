package io.getbit.app.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义规则转发配置
 *
 * <p>对标 SiverWXbot_plus 的 custom_forward_list 条目。</p>
 */
public class ForwardRule {

    /** 规则唯一 ID */
    private String id = "";

    /** true=全部来源模式，所有已监听私聊和群组均作为来源 */
    private boolean allSources = false;

    /** 手动指定的监听来源列表（allSources=false 时生效） */
    private List<String> sources = new ArrayList<>();

    /** 触发类型：keyword / all / sender */
    private String type = "keyword";

    /** type=keyword 时使用：关键词列表 */
    private List<String> keywords = new ArrayList<>();

    /** type=sender 时使用：发送人列表 */
    private List<String> senders = new ArrayList<>();

    /** 转发目标列表 */
    private List<String> targets = new ArrayList<>();

    /** 是否在转发时附带来源信息 */
    private boolean forwardWithSource = true;

    public ForwardRule() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isAllSources() { return allSources; }
    public void setAllSources(boolean allSources) { this.allSources = allSources; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public List<String> getSenders() { return senders; }
    public void setSenders(List<String> senders) { this.senders = senders; }

    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }

    public boolean isForwardWithSource() { return forwardWithSource; }
    public void setForwardWithSource(boolean forwardWithSource) { this.forwardWithSource = forwardWithSource; }
}
