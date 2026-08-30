package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.TicketUserDto;
import com.stellaris.dto.TicketUserIdDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.entity.TicketUser;
import com.stellaris.entity.User;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.TicketUserMapper;
import com.stellaris.mapper.UserMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.vo.TicketUserVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 购票人 service
 * @author: xz_y
 **/
@Service
public class TicketUserService extends ServiceImpl<TicketUserMapper, TicketUser> {
    
    @Autowired
    private TicketUserMapper ticketUserMapper;
    
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    public List<TicketUserVo> list(TicketUserListDto ticketUserListDto) {
        //先从缓存中查询
        List<TicketUserVo> ticketUserVoList = redisCache.getValueIsList(RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_USER_LIST, ticketUserListDto.getUserId()), TicketUserVo.class);
        if (CollectionUtil.isNotEmpty(ticketUserVoList)) {
            return ticketUserVoList;
        }
        LambdaQueryWrapper<TicketUser> ticketUserLambdaQueryWrapper = Wrappers.lambdaQuery(TicketUser.class)
                .eq(TicketUser::getUserId, ticketUserListDto.getUserId());
        List<TicketUser> ticketUsers = ticketUserMapper.selectList(ticketUserLambdaQueryWrapper);
        return BeanUtil.copyToList(ticketUsers,TicketUserVo.class);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void add(TicketUserDto ticketUserDto) {
        User user = userMapper.selectById(ticketUserDto.getUserId());
        if (Objects.isNull(user)) {
            throw new StellarisFrameException(BaseCode.USER_EMPTY);
        }
        LambdaQueryWrapper<TicketUser> ticketUserLambdaQueryWrapper = Wrappers.lambdaQuery(TicketUser.class)
                .eq(TicketUser::getUserId, ticketUserDto.getUserId())
                .eq(TicketUser::getIdType, ticketUserDto.getIdType())
                .eq(TicketUser::getIdNumber, ticketUserDto.getIdNumber());
        TicketUser ticketUser = ticketUserMapper.selectOne(ticketUserLambdaQueryWrapper);
        if (Objects.nonNull(ticketUser)) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_EXIST);
        }
        TicketUser addTicketUser = new TicketUser();
        BeanUtil.copyProperties(ticketUserDto,addTicketUser);
        addTicketUser.setId(uidGenerator.getUid());
        ticketUserMapper.insert(addTicketUser);
        delTicketUserVoListCache(String.valueOf(ticketUserDto.getUserId()));
    }
    @Transactional(rollbackFor = Exception.class)
    public void delete(TicketUserIdDto ticketUserIdDto) {
        TicketUser ticketUser = ticketUserMapper.selectById(ticketUserIdDto.getId());
        if (Objects.isNull(ticketUser)) {
            throw new StellarisFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        ticketUserMapper.deleteById(ticketUserIdDto.getId());
        delTicketUserVoListCache(String.valueOf(ticketUser.getUserId()));
    }
    
    public void delTicketUserVoListCache(String userId){
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST, userId));
    }
}
