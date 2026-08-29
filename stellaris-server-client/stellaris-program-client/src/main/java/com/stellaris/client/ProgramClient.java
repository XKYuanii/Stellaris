package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.*;
import com.stellaris.vo.ProgramRecordTaskVo;
import com.stellaris.vo.TicketCategoryDetailVo;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目服务 feign
 * @author: 阿星不是程序员
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"program-service",fallback = ProgramClientFallback.class)
public interface ProgramClient {
    
    /**
     * 更新座位为锁定和扣减余票数量
     * @param reduceRemainNumberDto 参数
     * @return 结果
     * */
    @PostMapping("/program/interior/reduce/remain/number")
    ApiResponse<Boolean> operateSeatLockAndTicketCategoryRemainNumber(ReduceRemainNumberDto reduceRemainNumberDto);

    /**
     * 查询票档集合
     * @param ticketCategoryDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/ticket/category/select/list")
    ApiResponse<List<TicketCategoryDetailVo>> selectList(TicketCategoryListDto ticketCategoryDto);
    
    /**
     * 获取所有节目id集合
     * @return 结果
     * */
    @PostMapping(value = "/program/all/list")
    ApiResponse<List<Long>> allList();
    
    /**
     * 获取节目对账记录任务集合
     * @param programRecordTaskListDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/program/record/task/select")
    ApiResponse<List<ProgramRecordTaskVo>> select(ProgramRecordTaskListDto programRecordTaskListDto);
    
    /**
     * 修改节目对账记录任务集合
     * @param programRecordTaskUpdateDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/program/record/task/update")
    ApiResponse<Integer> update(ProgramRecordTaskUpdateDto programRecordTaskUpdateDto);
    
    /**
     * 添加节目对账记录任务
     * @param orderTicketUserRecordAddDto 参数
     * @return 结果
     * */
    @PostMapping(value = "program/record/task/add")
    ApiResponse<Integer> add(ProgramRecordTaskAddDto orderTicketUserRecordAddDto);
    
    /**
     * 订单支付成功或者取消订单后对节目服务库的相关操作
     * @param programOperateDataDto 参数
     * @return 结果
     */
    @PostMapping(value = "/program/interior/operate/program/data")
    ApiResponse<Boolean> operateProgramData(ProgramOperateDataDto programOperateDataDto);

    /** v5 支付/取消同时推进数据库库存和新 Redis 座位模型。 */
    @PostMapping(value = "/program/interior/reference/reservation/transition")
    ApiResponse<Boolean> operateReferenceReservation(ProgramOperateDataDto programOperateDataDto);

    /**
     * 查询票档集合(通过节目查询)
     * @param ticketCategoryListByProgramDto 参数
     * @return 结果
     * */
    @PostMapping(value = "/ticket/category/select/list/by/program")
    ApiResponse<List<TicketCategoryDetailVo>> selectListByProgram(TicketCategoryListByProgramDto ticketCategoryListByProgramDto);
}
