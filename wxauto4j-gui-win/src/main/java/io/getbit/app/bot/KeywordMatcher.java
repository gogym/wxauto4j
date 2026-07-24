package io.getbit.app.bot;

import java.util.Map;

/**
 * 关键词匹配器
 *
 * <p>根据配置的关键词字典匹配消息内容，命中则返回对应回复。</p>
 */
public class KeywordMatcher {

    /**
     * 在消息中匹配关键词
     *
     * @param content     消息内容
     * @param keywordDict 关键词→回复映射
     * @return 匹配到的回复内容，未匹配返回 null
     */
    public String match(String content, Map<String, String> keywordDict) {
        if (content == null || content.isEmpty() || keywordDict == null || keywordDict.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, String> entry : keywordDict.entrySet()) {
            String keyword = entry.getKey();
            if (keyword != null && !keyword.isEmpty() && content.contains(keyword)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
