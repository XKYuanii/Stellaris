package com.stellaris.vo;

import lombok.Data;

import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户 返回vo
 * @author: xz_y
 **/
@Data
public class UserVo {
    
    private String id;
    
    private String name;
    
    private String password;
    
    private Integer age;
    
    private Integer status;
    
    private Date createTime;
    
    private String mobile;
    
    private Date editTime;
}
