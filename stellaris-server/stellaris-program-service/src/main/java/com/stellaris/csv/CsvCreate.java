package com.stellaris.csv;

import cn.hutool.core.io.FileUtil;
import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: jmeter csv文件生成工具类
 * @author: xz_y
 **/
public class CsvCreate {
    
    private static final String CSV_FILE_LOCATION = Paths.get("").toAbsolutePath() + "/csv";
    
    private static final String CSV_FILE_NAME = CSV_FILE_LOCATION + "/stellaris购买节目需要的压测数据.csv";
    
    static {
        if (!FileUtil.exist(CSV_FILE_LOCATION)) {
            FileUtil.mkdir(CSV_FILE_LOCATION);
        }
    }
    
    public static void main(String[] args) {
        createCsvFile();
    }
    
    public static void createCsvFile(){
        List<String[]> csvCompleteData = createCsvCompleteData();
        try (CSVWriter writer = new CSVWriter(new FileWriter(CSV_FILE_NAME, StandardCharsets.UTF_8))) {
            writer.writeAll(csvCompleteData);
        } catch (IOException e) {
            System.out.println("生成失败:"+e.getMessage());
        }
    }
    
    public static List<String[]> createCsvCompleteData() {
        List<String[]> csvData = new ArrayList<>();
        csvData.add(new String[]{"programId", "ticketCategoryId"});
        //节目id
        String programId = "34";
        Map<String, Integer> data = createData();
        for (final Entry<String, Integer> entry : data.entrySet()) {
            //节目票档id
            String ticketCategoryId = entry.getKey();
            //购买数量
            Integer purchaseQuantity = entry.getValue();
            for (int i = 1; i <= purchaseQuantity; i++) {
                csvData.add(new String[]{programId, ticketCategoryId});
            }
        }
        Collections.shuffle(csvData.subList(1, csvData.size()));
        return csvData;
    }
    
    /**
     * 生成数据
     * key: 节目票档id
     * value: 购买数量
     * */
    public static Map<String,Integer> createData(){
        Map<String,Integer> map = new HashMap<>(10);
        map.put("46",10000);
        map.put("45",10000);
        map.put("44",10000);
        map.put("43",15000);
        map.put("42",15000);
        map.put("41",20000);
        map.put("40",20000);
        return map;
    }
}
