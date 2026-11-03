package org.javaup.image.api.dto;

/** 图片的来源类型。 */
public enum VisualSourceType {
    MODEL_SNAPSHOT, // 3D 手部模型的固定视角截图
    STANDARD_REFERENCE, // 系统提供的标准手部图片
    USER_UPLOAD // 用户上传的图片
}
