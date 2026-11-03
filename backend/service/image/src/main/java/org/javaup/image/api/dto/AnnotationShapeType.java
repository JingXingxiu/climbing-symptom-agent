package org.javaup.image.api.dto;

/** 图片标注的形状。 */
public enum AnnotationShapeType {
    POINT, // 点标记
    CIRCLE, // 圆形区域
    POLYLINE, // 连续折线或自由画线
    POLYGON // 闭合多边形区域
}
