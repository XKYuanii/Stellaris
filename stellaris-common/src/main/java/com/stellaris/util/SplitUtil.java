package com.stellaris.util;

import static com.stellaris.constant.Constant.GLIDE_LINE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 分割工具
 * @author: xz_y
 **/
public class SplitUtil {
    
    public static String[] toSplit(String str) {
        return str.split(GLIDE_LINE);
    }
}
