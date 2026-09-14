package com.stellaris.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderMaterializationVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long orderNumber;
    /** PROCESSING / CREATED / REJECTED。 */
    private String status;
    private String rejectCode;
}
