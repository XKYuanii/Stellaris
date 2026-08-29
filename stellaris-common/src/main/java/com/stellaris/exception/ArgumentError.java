package com.stellaris.exception;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 参数错误
 * @author: 阿星不是程序员
 **/
@Data
public class ArgumentError {
	
	private String argumentName;
	
	private String message;
}
