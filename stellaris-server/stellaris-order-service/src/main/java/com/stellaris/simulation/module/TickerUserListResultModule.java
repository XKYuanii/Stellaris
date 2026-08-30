package com.stellaris.simulation.module;

import com.stellaris.vo.TicketUserVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户登录结果
 * @author: xz_y
 **/
@EqualsAndHashCode(callSuper = true)
@Data
public class TickerUserListResultModule extends ApiResponseModule{

    private List<TicketUserVo> data;
}
