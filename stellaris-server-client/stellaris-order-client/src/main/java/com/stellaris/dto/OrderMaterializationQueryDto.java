package com.stellaris.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class OrderMaterializationQueryDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull
    private Long orderNumber;
}
