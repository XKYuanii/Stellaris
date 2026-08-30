package com.stellaris.exception;

import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 参数错误
 * @author: xz_y
 **/
@Data
public class ArgumentError {
	
	private String argumentName;
	
	private String message;
}
