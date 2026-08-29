package com.stellaris.util;


import cn.hutool.crypto.KeyUtil;
import com.alibaba.fastjson.JSON;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: rsa工具
 * @author: 阿星不是程序员
 **/
@Slf4j
public class RsaTool {
	
	private final static Integer KEY_SIZE = 2048;
	
	/**
	 * RSA最大加密明文大小
	 */
	private static final int MAX_ENCRYPT_BLOCK = 117;
	
	/**
	 * RSA最大解密密文大小
	 */
	private static final int MAX_DECRYPT_BLOCK = 256;
	
	public static final String KEY_ALGORITHM = "RSA";
    
    public static final String PUBLIC_KEY = "publicKey";
    public static final String PRIVATE_KEY = "privateKey";
	
	/**
	 * 生成公私钥
	 */
	public static Map<String, String> getKey() {
		Map<String, String> pubPriKey = new HashMap<>(8);
		KeyPair keyPair = KeyUtil.generateKeyPair(KEY_ALGORITHM, KEY_SIZE);
		String publicKeyStr = java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
		String privateKeyStr = java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
		pubPriKey.put(PUBLIC_KEY, publicKeyStr);
		pubPriKey.put(PRIVATE_KEY, privateKeyStr);
		return pubPriKey;
	}
	
	
	/**
	 * RSA加密
	 *
	 * @param data
	 *            待加密数据
	 * @param publicKey
	 *            公钥
	 * @return
	 */
	public static String encrypt(String data, PublicKey publicKey) throws Exception {
		Cipher cipher = Cipher.getInstance("RSA");
		cipher.init(Cipher.ENCRYPT_MODE, publicKey);
		int inputLen = data.getBytes().length;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int offset = 0;
		byte[] cache;
		int i = 0;
		// 对数据分段加密
		while (inputLen - offset > 0) {
			if (inputLen - offset > MAX_ENCRYPT_BLOCK) {
				cache = cipher.doFinal(data.getBytes(), offset, MAX_ENCRYPT_BLOCK);
			} else {
				cache = cipher.doFinal(data.getBytes(), offset, inputLen - offset);
			}
			out.write(cache, 0, cache.length);
			i++;
			offset = i * MAX_ENCRYPT_BLOCK;
		}
		byte[] encryptedData = out.toByteArray();
		out.close();
		// 获取加密内容使用base64进行编码,并以UTF-8为标准转化成字符串
		// 加密后的字符串
		return new String(Base64.encodeBase64(encryptedData));
	}
	
	/**
	 * RSA解密
	 *
	 * @param data
	 *            待解密数据
	 * @param privateKey
	 *            私钥
	 * @return
	 */
	public static String decrypt(String data, PrivateKey privateKey) throws Exception {
		Cipher cipher = Cipher.getInstance("RSA");
		cipher.init(Cipher.DECRYPT_MODE, privateKey);
		byte[] dataBytes = Base64.decodeBase64(data.replaceAll("%2B","+").getBytes());
		int inputLen = dataBytes.length;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int offset = 0;
		byte[] cache;
		int i = 0;
		// 对数据分段解密
		while (inputLen - offset > 0) {
			if (inputLen - offset > MAX_DECRYPT_BLOCK) {
				cache = cipher.doFinal(dataBytes, offset, MAX_DECRYPT_BLOCK);
			} else {
				cache = cipher.doFinal(dataBytes, offset, inputLen - offset);
			}
			out.write(cache, 0, cache.length);
			i++;
			offset = i * MAX_DECRYPT_BLOCK;
		}
		byte[] decryptedData = out.toByteArray();
		out.close();
		// 解密后的内容
		return new String(decryptedData, "UTF-8");
	}
	
	/**
	 * 37 * 获取私钥 38 * 39 * @param privateKey 私钥字符串 40 * @return 41
	 */
	public static PrivateKey getPrivateKey(String privateKey) throws Exception {
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		byte[] decodedKey = Base64.decodeBase64(privateKey.getBytes());
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKey);
		return keyFactory.generatePrivate(keySpec);
	}
	
	/**
	 * 根据私钥字符串进行解密
	 * @param data
	 * @param privateKeyStr
	 * @return
	 * @throws Exception
	 */
	public static String decrypt(String data, String privateKeyStr) {
		try {
			PrivateKey privateKey2 = RsaTool.getPrivateKey(privateKeyStr);
			return RsaTool.decrypt(data, privateKey2);
		}catch (Exception e) {
			log.error("decrypt error",e);
			throw new StellarisFrameException(BaseCode.RSA_DECRYPT_ERROR);
		}
	}
	
	/**
	 * 根据公钥串对数据进行RSA加密
	 * @param data
	 * @param publicKey
	 * @return
	 * @throws Exception
	 */
	public static String encrypt(String data, String publicKey)  {
		try {
			PublicKey publicKeyTmp = RsaTool.getPublicKey(publicKey);
			return encrypt(data,publicKeyTmp);
		}catch (Exception e) {
			log.error("encrypt error",e);
			throw new StellarisFrameException(BaseCode.RSA_ENCRYPT_ERROR);
		}
			
	}
		
	/**
	 * 获取公钥
	 *
	 * @param publicKey
	 *            公钥字符串
	 * @return
	 */
	public static PublicKey getPublicKey(String publicKey) throws Exception {
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");
		byte[] decodedKey = Base64.decodeBase64(publicKey.getBytes());
		X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decodedKey);
		return keyFactory.generatePublic(keySpec);
	}
	
	public static void main(String[] args) throws Exception {
		String publicKey = System.getenv("STELLARIS_RSA_PUBLIC_KEY");
		String privateKey = System.getenv("STELLARIS_RSA_PRIVATE_KEY");
		Map<String,Object> businessBodyMap = new HashMap<>(8);
		businessBodyMap.put("id","1");
		businessBodyMap.put("sleepTime","10");
		String encrypt = RsaTool.encrypt(JSON.toJSONString(businessBodyMap), publicKey);
		System.out.println("加密后:" + encrypt);
		String decrypt = RsaTool.decrypt(encrypt, privateKey);
		System.out.println("解密后:" + decrypt);
	}

}
