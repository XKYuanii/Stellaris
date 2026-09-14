package com.stellaris.service;

import com.stellaris.domain.OrderCreateEvent;
import com.stellaris.dto.OrderTicketUserCreateDto;
import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.vo.ProgramVo;
import com.stellaris.vo.SeatVo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/** Builds the immutable order event used by the v5 reservation pipeline. */
@Service
public class ProgramOrderService {

    private final ProgramService programService;

    public ProgramOrderService(ProgramService programService) {
        this.programService = programService;
    }

    /**
     * Builds the complete v5 order event before the reservation Lua script atomically locks seats
     * and appends that event to Redis Stream.
     */
    public OrderCreateEvent buildReferenceOrderMessage(ProgramOrderCreateDto request, List<SeatVo> seats,
                                                     Long orderNumber) {
        if (seats == null || request.getTicketUserIdList() == null
                || seats.size() != request.getTicketUserIdList().size()) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_COUNT_UNEQUAL_SEAT_COUNT);
        }

        ProgramVo program = programService.simpleGetProgramAndShowMultipleCache(request.getProgramId());
        OrderCreateEvent message = new OrderCreateEvent();
        message.setOrderNumber(orderNumber);
        message.setProgramId(request.getProgramId());
        message.setProgramItemPicture(program.getItemPicture());
        message.setUserId(request.getUserId());
        message.setProgramTitle(program.getTitle());
        message.setProgramPlace(program.getPlace());
        message.setProgramShowTime(program.getShowTime());
        message.setProgramPermitChooseSeat(program.getPermitChooseSeat());
        message.setOrderPrice(seats.stream().map(SeatVo::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
        Date createTime = new Date();
        message.setCreateOrderTime(createTime);

        List<OrderTicketUserCreateDto> ticketUsers = new ArrayList<>(seats.size());
        for (int index = 0; index < seats.size(); index++) {
            SeatVo seat = Optional.ofNullable(seats.get(index))
                    .orElseThrow(() -> new StellarisFrameException(BaseCode.SEAT_NOT_EXIST));
            OrderTicketUserCreateDto ticketUser = new OrderTicketUserCreateDto();
            ticketUser.setOrderNumber(orderNumber);
            ticketUser.setProgramId(request.getProgramId());
            ticketUser.setUserId(request.getUserId());
            ticketUser.setTicketUserId(request.getTicketUserIdList().get(index));
            ticketUser.setSeatId(seat.getId());
            ticketUser.setSeatInfo(seat.getRowCode() + "排" + seat.getColCode() + "列");
            ticketUser.setTicketCategoryId(seat.getTicketCategoryId());
            ticketUser.setOrderPrice(seat.getPrice());
            ticketUser.setCreateOrderTime(createTime);
            ticketUsers.add(ticketUser);
        }
        message.setOrderTicketUserCreateDtoList(ticketUsers);
        return message;
    }
}
