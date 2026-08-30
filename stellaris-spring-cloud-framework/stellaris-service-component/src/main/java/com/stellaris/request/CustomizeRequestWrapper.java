package com.stellaris.request;

import com.stellaris.util.StringUtil;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: request包装
 * @author: xz_y
 **/
public class CustomizeRequestWrapper extends HttpServletRequestWrapper {
    
    private final String requestBody;
        
    
    public CustomizeRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        requestBody = StringUtil.inputStreamConvertString(request.getInputStream());
    }
    
    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = 
                new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
            
            @Override
            public boolean isFinished() {
                return false;
            }
            
            @Override
            public boolean isReady() {
                return false;
            }
            
            @Override
            public void setReadListener(ReadListener listener) {
                
            }
        };
    }
    
    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }
    
    public String getRequestBody() {
        return this.requestBody;
    }
}
