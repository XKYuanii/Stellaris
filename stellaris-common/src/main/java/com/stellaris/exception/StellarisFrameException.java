package com.stellaris.exception;

import com.stellaris.common.ApiResponse;
import com.stellaris.enums.BaseCode;
import lombok.Data;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 业务异常
 * @author: 阿星不是程序员
 **/
@Data
public class StellarisFrameException extends BaseException {

	private Integer code;
	
	private String message;

	public StellarisFrameException() {
		super();
	}

	public StellarisFrameException(String message) {
		super(message);
	}
	
	
	public StellarisFrameException(String code, String message) {
		super(message);
		this.code = Integer.parseInt(code);
		this.message = message;
	}
	
	public StellarisFrameException(Integer code, String message) {
		super(message);
		this.code = code;
		this.message = message;
	}
	
	public StellarisFrameException(BaseCode baseCode) {
		super(baseCode.getMsg());
		this.code = baseCode.getCode();
		this.message = baseCode.getMsg();
	}
	
	public StellarisFrameException(ApiResponse apiResponse) {
		super(apiResponse.getMessage());
		this.code = apiResponse.getCode();
		this.message = apiResponse.getMessage();
	}

	public StellarisFrameException(Throwable cause) {
		super(cause);
	}

	public StellarisFrameException(String message, Throwable cause) {
		super(message, cause);
		this.message = message;
	}

	public StellarisFrameException(Integer code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.message = message;
	}
}
