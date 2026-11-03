package org.javaup.learning.springai.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import lombok.extern.log4j.Log4j2;
import org.springframework.ai.tool.annotation.Tool;

/** 下一课的工具练习：当前只是普通 Java 方法，还未暴露给模型。 */
@Log4j2
public class DemoTools {

    // TODO(owner: learner): 添加 @Tool，并写清工具用途；随后在 ChatClient 请求中注册这个对象。
    @Tool(description = "getTime")
    public String currentTime() {
        log.info("Java 工具 currentTime 被执行");
        return ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString();
    }
}
