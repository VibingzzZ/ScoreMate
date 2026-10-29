package com.abing.assessment.domain.model;

/**
 * 一条扣分事由。扣分基本读不出来（宿舍不合格、体测未达标都是认定的，没证书可拍），
 * 所以以<b>用户手填</b>为主；事由文字必填——评定表要求写「1、xxxx.xx.xx，因xx留校察看」，
 * 只填数字会被退回。
 *
 * @param dimension 归属维度
 * @param reason    事由原文，直接写进评定表
 * @param points    扣多少分，<b>填正数</b>（20 表示扣 20 分），计算时统一按减号处理
 */
public record PenaltyEntry(String dimension, String reason, Double points) {

    public boolean usable() {
        return dimension != null && !dimension.isBlank() && points != null && points > 0;
    }

    /** 落到评定表「得分」列的文字，形如 {@code -20} */
    public String scoreText() {
        if (points == null) {
            return "";
        }
        double v = -points;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 落到评定表「明细」列的文字 */
    public String reasonText() {
        return reason == null || reason.isBlank() ? "（未填写事由，请补上）" : reason.trim();
    }
}
