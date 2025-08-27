package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GTVSpider extends Spider {

    private static final String TAG = "GTVSpider";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final int DEFAULT_TIMEOUT = 15;
    private static final long CACHE_TTL = 2 * 3600 * 1000;

    private String user;
    private String password;
    private String token;
    private Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    // 简化频道映射
    private static final Map<String, String> CHANNEL_NAMES = new HashMap<String, String>() {{
        put("4gtv-4gtv001", "民视台湾台");
        put("4gtv-4gtv002", "民视");
        put("4gtv-4gtv155", "民视");
        put("4gtv-4gtv009", "中天新闻台");
        put("4gtv-4gtv010", "非凡新闻台");
        put("4gtv-4gtv040", "中视");
        put("4gtv-4gtv041", "华视");
        put("4gtv-4gtv042", "公视戏剧");
        put("4gtv-4gtv043", "客家电视台");
        put("4gtv-4gtv066", "台视");
        put("4gtv-4gtv072", "TVBS新闻");
        put("4gtv-4gtv073", "TVBS");
        put("4gtv-4gtv074", "中视新闻");
        put("4gtv-4gtv075", "镜电视新闻台");
        put("4gtv-4gtv152", "东森新闻台");
        put("4gtv-4gtv153", "东森财经新闻台");
        put("4gtv-4gtv156", "寰宇新闻台湾台");
        put("4gtv-4gtv158", "寰宇财经台");
    }};

    @Override
    public void init(Context context, String extend) {
        try {
            if (extend != null && !extend.isEmpty()) {
                JSONObject json = new JSONObject(extend);
                user = json.optString("user", "");
                password = json.optString("password", "");
                token = json.optString("token", "");
            }
        } catch (JSONException e) {
            user = "";
            password = "";
            token = "";
        }
    }

    @Override
    public String homeContent(boolean filter) {
        return "{}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "{}";
    }

    @Override
    public String detailContent(List<String> ids) {
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "{}";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        if (id != null && id.startsWith("proxy://")) {
            return handleProxy(id);
        }
        return "{}";
    }

    private String handleProxy(String url) {
        try {
            Map<String, String> params = parseProxyUrl(url);
            String channelId = params.get("id");
            String type = params.get("type");
            if (type == null) type = "m3u8";
            
            if (channelId != null && channelId.startsWith("4gtv-live")) {
                return handle4GTVChannel(channelId, type);
            } else if (channelId != null) {
                return handleSimpleChannel(channelId, type);
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
        return "{}";
    }

    private Map<String, String> parseProxyUrl(String url) {
        Map<String, String> params = new HashMap<>();
        try {
            String query = url.replace("proxy://do=gtv&", "");
            String[] parts = query.split("&");
            for (String part : parts) {
                String[] keyValue = part.split("=");
                if (keyValue.length == 2) {
                    params.put(keyValue[0], keyValue[1]);
                }
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return params;
    }

    private String handle4GTVChannel(String channelId, String type) {
        try {
            long now = System.currentTimeMillis();
            CacheEntry entry = cache.get(channelId);
            
            if (entry != null && now - entry.timestamp < CACHE_TTL) {
                return buildPlayResult(entry.url, type);
            }
            
            // 直接返回一个测试URL，实际应用中应该调用API获取
            String testUrl = "https://example.com/test.m3u8";
            cache.put(channelId, new CacheEntry(now, testUrl));
            
            return buildPlayResult(testUrl, type);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String handleSimpleChannel(String channelId, String type) {
        try {
            // 为简单频道生成一个基本的M3U8 URL
            String m3u8Url = "https://example.com/" + channelId + ".m3u8";
            return buildPlayResult(m3u8Url, type);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String buildPlayResult(String url, String type) {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("parse", 0);
            result.addProperty("playUrl", "");
            
            if ("m3u8".equals(type)) {
                JsonObject header = new JsonObject();
                header.addProperty("User-Agent", DEFAULT_USER_AGENT);
                header.addProperty("Referer", "https://www.4gtv.tv/");
                header.addProperty("Origin", "https://www.4gtv.tv/");
                result.add("header", header);
            }
            
            result.addProperty("url", url);
            return new Gson().toJson(result);
        } catch (Exception e) {
            return "{\"error\":\"构建播放结果失败: " + e.getMessage() + "\"}";
        }
    }

    public String liveContent(Map<String, String> params) {
        try {
            StringBuilder sb = new StringBuilder();
            
            // 添加4GTV频道
            sb.append("4GTV,#genre#\n");
            for (String channelId : CHANNEL_NAMES.keySet()) {
                String channelName = CHANNEL_NAMES.get(channelId);
                String proxyUrl = "proxy://do=gtv&id=" + channelId + "&type=m3u8";
                sb.append(channelName).append(",").append(proxyUrl).append("\n");
            }
            
            // 添加一些基本频道
            sb.append("基本频道,#genre#\n");
            sb.append("测试频道1,proxy://do=gtv&id=test1&type=m3u8\n");
            sb.append("测试频道2,proxy://do=gtv&id=test2&type=m3u8\n");
            sb.append("测试频道3,proxy://do=gtv&id=test3&type=m3u8\n");
            
            return sb.toString();
        } catch (Exception e) {
            return "4GTV,#genre#\n错误," + e.getMessage() + "\n";
        }
    }

    // 简化版本的HTTP请求方法
    private String httpGet(String urlString, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT * 1000);
            connection.setReadTimeout(DEFAULT_TIMEOUT * 1000);
            
            // 设置请求头
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                
                return response.toString();
            }
        } catch (Exception e) {
            // 忽略异常
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private String httpPost(String urlString, String jsonData, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(DEFAULT_TIMEOUT * 1000);
            connection.setReadTimeout(DEFAULT_TIMEOUT * 1000);
            connection.setDoOutput(true);
            
            // 设置请求头
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            
            // 发送请求体
            if (jsonData != null) {
                byte[] postData = jsonData.getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(postData);
            }
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                
                return response.toString();
            }
        } catch (Exception e) {
            // 忽略异常
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private static class CacheEntry {
        long timestamp;
        String url;
        
        CacheEntry(long timestamp, String url) {
            this.timestamp = timestamp;
            this.url = url;
        }
    }
}
