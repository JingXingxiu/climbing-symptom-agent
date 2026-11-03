package org.javaup.image.api.dto;

/** 图片上传与存储的状态。 */
public enum PictureUploadStatus {
    INITIATED, // 已初始化上传记录
    UPLOADED, // 原图已上传
    AVAILABLE, // 图片已可供业务使用
    FAILED, // 上传或存储失败
    DELETED // 已删除
}
