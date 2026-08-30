package com.stellaris.service;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.ParentProgramCategoryDto;
import com.stellaris.dto.ProgramCategoryAddDto;
import com.stellaris.dto.ProgramCategoryDto;
import com.stellaris.entity.ProgramCategory;
import com.stellaris.mapper.ProgramCategoryMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.vo.ProgramCategoryVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.stellaris.core.DistributedLockConstants.PROGRAM_CATEGORY_LOCK;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目类型 service
 * @author: xz_y
 **/
@Service
public class ProgramCategoryService extends ServiceImpl<ProgramCategoryMapper, ProgramCategory> {
    
    @Autowired
    private ProgramCategoryMapper programCategoryMapper;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private RedisCache redisCache;
    
    /**
     * 查询所有节目类型
     * */
    public List<ProgramCategoryVo> selectAll(){
        QueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.emptyWrapper();
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategoryList,ProgramCategoryVo.class);
    }
            
    public List<ProgramCategoryVo> selectByType(ProgramCategoryDto programCategoryDto) {
        LambdaQueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .eq(ProgramCategory::getType, programCategoryDto.getType());
        List<ProgramCategory> programCategories = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategories,ProgramCategoryVo.class);
    }
    
    public List<ProgramCategoryVo> selectByParentProgramCategoryId(ParentProgramCategoryDto parentProgramCategoryDto) {
        LambdaQueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.lambdaQuery(ProgramCategory.class)
                .eq(ProgramCategory::getParentId, parentProgramCategoryDto.getParentProgramCategoryId());
        List<ProgramCategory> programCategories = programCategoryMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(programCategories,ProgramCategoryVo.class);
    }
    
    @Transactional(rollbackFor = Exception.class)
    @ServiceLock(lockType= LockType.Write,name = PROGRAM_CATEGORY_LOCK,keys = {"all"})
    public void saveBatch(final List<ProgramCategoryAddDto> programCategoryAddDtoList) {
        List<ProgramCategory> programCategoryList = programCategoryAddDtoList.stream().map((programCategoryAddDto) -> {
            ProgramCategory programCategory = new ProgramCategory();
            BeanUtil.copyProperties(programCategoryAddDto, programCategory);
            programCategory.setId(uidGenerator.getUid());
            return programCategory;
        }).collect(Collectors.toList());
        
        if (CollectionUtil.isNotEmpty(programCategoryList)) {
            this.saveBatch(programCategoryList);
            Map<String, ProgramCategory> programCategoryMap = programCategoryList.stream().collect(
                    Collectors.toMap(p -> String.valueOf(p.getId()), p -> p, (v1, v2) -> v2));
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_CATEGORY_HASH),programCategoryMap);
        }
        
    }
    
    public ProgramCategory getProgramCategory(Long programCategoryId){
        ProgramCategory programCategory = redisCache.getForHash(RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_CATEGORY_HASH), String.valueOf(programCategoryId), ProgramCategory.class);
        if (Objects.isNull(programCategory)) {
            Map<String, ProgramCategory> programCategoryMap = programCategoryRedisDataInit();
            return programCategoryMap.get(String.valueOf(programCategoryId));
        }
        return programCategory;
    }
    
    @ServiceLock(lockType= LockType.Write,name = PROGRAM_CATEGORY_LOCK,keys = {"#all"})
    public Map<String, ProgramCategory> programCategoryRedisDataInit(){
        Map<String, ProgramCategory> programCategoryMap = new HashMap<>(64);
        QueryWrapper<ProgramCategory> lambdaQueryWrapper = Wrappers.emptyWrapper();
        List<ProgramCategory> programCategoryList = programCategoryMapper.selectList(lambdaQueryWrapper);
        if (CollectionUtil.isNotEmpty(programCategoryList)) {
            programCategoryMap = programCategoryList.stream().collect(
                    Collectors.toMap(p -> String.valueOf(p.getId()), p -> p, (v1, v2) -> v2));
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_CATEGORY_HASH),programCategoryMap);
        }
        return programCategoryMap;
    }
}
