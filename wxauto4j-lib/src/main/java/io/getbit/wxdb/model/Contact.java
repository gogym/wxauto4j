package io.getbit.wxdb.model;

/**
 * 微信联系人
 */
public class Contact {
    private String username;
    private String nickName;
    private String remark;
    private String alias;
    private String smallHeadImgUrl;
    private String bigHeadImgUrl;

    public Contact() {}

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getNickName() { return nickName; }
    public void setNickName(String nickName) { this.nickName = nickName; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getSmallHeadImgUrl() { return smallHeadImgUrl; }
    public void setSmallHeadImgUrl(String url) { this.smallHeadImgUrl = url; }

    public String getBigHeadImgUrl() { return bigHeadImgUrl; }
    public void setBigHeadImgUrl(String url) { this.bigHeadImgUrl = url; }

    /**
     * 获取显示名称（优先 remark，其次 nickName）
     */
    public String getDisplayName() {
        if (remark != null && !remark.isEmpty()) return remark;
        if (nickName != null && !nickName.isEmpty()) return nickName;
        return username;
    }

    /**
     * 是否为群聊
     */
    public boolean isChatroom() {
        return username != null && username.endsWith("@chatroom");
    }

    /**
     * 是否为公众号
     */
    public boolean isOfficialAccount() {
        return username != null && username.startsWith("gh_");
    }

    @Override
    public String toString() {
        return "Contact{username='" + username + "', nick='" + nickName +
                "', remark='" + remark + "'}";
    }
}
