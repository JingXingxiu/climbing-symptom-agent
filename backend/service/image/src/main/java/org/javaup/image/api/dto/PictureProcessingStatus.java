package org.javaup.image.api.dto;

/** 图片异步处理的状态。 */
public enum PictureProcessingStatus {
    PENDING, // 等待处理
    PROCESSING, // 正在处理
    SUCCEEDED, // 全部处理成功
    PARTIAL, // 部分处理成功
    FAILED, // 处理失败
    NOT_REQUIRED // 无需处理
}
