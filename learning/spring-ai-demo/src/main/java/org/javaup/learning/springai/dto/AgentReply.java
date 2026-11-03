package org.javaup.learning.springai.dto;

/** 仅用于练习结构化输出；COMPLETE 表示信息整理结束，不表示医学分析完成。 */
public record AgentReply(
        Action action,
        String message
) {

    public enum Action {
        ASK_QUESTION,
        COMPLETE
    }
}
