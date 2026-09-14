package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单 实体
 * @author: xz_y
 **/
@Data
@TableName("d_order")
public class Order extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;
    
    /**
     * 订单编号
     * */
    private Long orderNumber;
    
    /** Redis reservation owner persisted with the order. */
    private String intentId;

    /**
     * 节目表id
     */
    private Long programId;
    
    /**
     * 节目图片介绍
     * */
    private String programItemPicture;

    /**
     * 用户id
     */
    private Long userId;
    
    /**
     * 节目标题
     * */
    private String programTitle;
    
    /**
     * 节目地点
     * */
    private String programPlace;
    
    /**
     * 节目演出时间
     * */
    private Date programShowTime;
    
    /**
     * 节目是否允许选座 1:允许选座 0:不允许选座
     * */
    private Integer programPermitChooseSeat;

    /**
     * 配送方式
     */
    private String distributionMode;

    /**
     * 取票方式
     */
    private String takeTicketMode;

    /**
     * 订单价格
     */
    private BigDecimal orderPrice;

    /**
     * 支付订单方式
     */
    private Integer payOrderType;

    /**
     * 订单状态 1:未支付 2:已取消 3:已支付 4:已退单
     */
    private Integer orderStatus;
    
    /**
     * 对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕
     */
    /**
     * 创建订单的版本 1 2 3 4
     * */
    /**
     * 生成订单时间
     */
    private Date createOrderTime;

    /** 持久化的未支付截止时间，避免配置变化重算历史订单。 */
    private Date expireTime;

    /** 过期关单失败次数；失败项延期后不会持续占据扫描队头。 */
    private Integer expiryRetryCount;

    /** 下一次允许尝试过期关单的时间，null 表示立即可扫描。 */
    private Date expiryNextRetryTime;

    /** 最近一次关单失败摘要，供人工排查。 */
    private String expiryLastError;

    /**
     * 取消订单时间
     */
    private Date cancelOrderTime;

    /**
     * 支付订单时间
     */
    private Date payOrderTime;
}
