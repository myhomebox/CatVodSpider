package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GTVSpider extends Spider {

    private static final String TAG = "GTVSpider";
    private static final String DEFAULT_USER_AGENT = "%E5%9B%9B%E5%AD%A3%E7%B7%9A%E4%B8%8A/4 CFNetwork/3826.500.131 Darwin/24.5.0";
    private static final int DEFAULT_TIMEOUT = 10;
    private static final long CACHE_TTL = 2 * 3600 * 1000; // 2小时有效期

    private String user;
    private String password;
    private String token;
    private Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // LITV频道映射
    private static final Map<String, String[]> LITV_VIDEO_SOUND_MAPPING = new HashMap<String, String[]>() {{
        put("4gtv-4gtv001", new String[]{"1", "6"});
        put("4gtv-4gtv002", new String[]{"1", "10"});
        put("4gtv-4gtv155", new String[]{"1", "6"});
        // 其他频道映射...
    }};

    @Override
    public void init(Context context, String extend) {
        Log.d(TAG, "初始化GTVSpider，参数: " + extend);
        try {
            JSONObject json = new JSONObject(extend);
            user = json.optString("user", "");
            password = json.optString("password", "");
            token = json.optString("token", "");
            
            Log.d(TAG, "用户: " + user + ", 密码: " + (password.isEmpty() ? "空" : "已设置") + ", 令牌: " + (token.isEmpty() ? "空" : "已设置"));
        } catch (JSONException e) {
            Log.e(TAG, "解析初始化参数失败", e);
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
        Log.d(TAG, "播放内容请求: " + id);
        if (id != null && id.startsWith("proxy://")) {
            return handleProxy(id);
        }
        return "{}";
    }

    private String handleProxy(String url) {
        Log.d(TAG, "处理代理请求: " + url);
        try {
            Map<String, String> params = parseProxyUrl(url);
            String channelId = params.get("id");
            String type = params.get("type");
            if (type == null) type = "m3u8";
            
            if (channelId != null && channelId.startsWith("4gtv-live")) {
                return handle4GTVChannel(channelId, type);
            } else if (channelId != null) {
                return handleLITVChannel(channelId, type);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理代理请求失败", e);
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
            Log.d(TAG, "解析代理URL参数: " + params);
        } catch (Exception e) {
            Log.e(TAG, "解析代理URL失败", e);
        }
        return params;
    }

    private String handle4GTVChannel(String channelId, String type) {
        Log.d(TAG, "处理4GTV频道: " + channelId + ", 类型: " + type);
        try {
            long now = System.currentTimeMillis();
            CacheEntry entry = cache.get(channelId);
            
            if (entry != null && now - entry.timestamp < CACHE_TTL) {
                Log.d(TAG, "使用缓存中的URL: " + entry.url);
                return buildPlayResult(entry.url, type);
            }
            
            String fsencKey = generateUUID(user);
            String authVal = generate4GTVAuth();
            String fsValue = signIn4GTV(user, password, fsencKey, authVal);
            
            if (fsValue == null || fsValue.isEmpty()) {
                throw new Exception("登录失败");
            }
            
            Map<String, String> channels = getAllChannels();
            String fnChannelId = channels.get(channelId);
            
            if (fnChannelId == null || fnChannelId.isEmpty()) {
                throw new Exception("频道未找到: " + channelId);
            }
            
            String masterUrl = get4GTVChannelUrl(channelId, fnChannelId, fsValue, fsencKey, authVal);
            if (masterUrl == null || masterUrl.isEmpty()) {
                throw new Exception("无法获取流URL");
            }
            
            String highestUrl = getHighestBitrateUrl(masterUrl, fnChannelId);
            
            cache.put(channelId, new CacheEntry(now, highestUrl));
            
            Log.d(TAG, "获取到播放URL: " + highestUrl);
            return buildPlayResult(highestUrl, type);
        } catch (Exception e) {
            Log.e(TAG, "处理4GTV频道失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String handleLITVChannel(String channelId, String type) {
        Log.d(TAG, "处理LITV频道: " + channelId + ", 类型: " + type);
        try {
            if (!LITV_VIDEO_SOUND_MAPPING.containsKey(channelId)) {
                throw new Exception("LITV频道未找到: " + channelId);
            }
            
            if ("ts".equals(type)) {
                String tsUrl = generateLITVTsUrl(channelId);
                Log.d(TAG, "生成LITV TS URL: " + tsUrl);
                return buildPlayResult(tsUrl, type);
            } else {
                String m3u8Content = generateLITVM3U8(channelId);
                Log.d(TAG, "生成LITV M3U8内容");
                return buildPlayResult(m3u8Content, type);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理LITV频道失败", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String generateLITVTsUrl(String channelId) {
        long timestamp = (System.currentTimeMillis() / 4000) - 355017625;
        long t = timestamp * 4;
        String[] mapping = LITV_VIDEO_SOUND_MAPPING.get(channelId);
        
        return String.format(Locale.getDefault(),
            "https://ntd-tgc.cdn.hinet.net/live/pool/%s/litv-pc/" +
            "%s-avc1_6000000=%s-mp4a_134000_zho=%s-begin=%d0000000-dur=40000000-seq=%d.ts",
            channelId, channelId, mapping[0], mapping[1], t, timestamp);
    }

    private String generateLITVM3U8(String channelId) {
        long timestamp = (System.currentTimeMillis() / 4000) - 355017625;
        long t = timestamp * 4;
        String[] mapping = LITV_VIDEO_SOUND_MAPPING.get(channelId);
        
        StringBuilder m3u8 = new StringBuilder();
        m3u8.append("#EXTM3U\n");
        m3u8.append("#EXT-X-VERSION:3\n");
        m3u8.append("#EXT-X-TARGETDURATION:4\n");
        m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(timestamp).append("\n");
        
        for (int i = 0; i < 10; i++) {
            m3u8.append("#EXTINF:4.0000,\n");
            String tsUrl = String.format(Locale.getDefault(),
                "https://ntd-tgc.cdn.hinet.net/live/pool/%s/litv-pc/" +
                "%s-avc1_6000000=%s-mp4a_134000_zho=%s-begin=%d0000000-dur=40000000-seq=%d.ts",
                channelId, channelId, mapping[0], mapping[1], t, timestamp);
            m3u8.append(tsUrl).append("\n");
            timestamp++;
            t += 4;
        }
        
        m3u8.append("#EXT-X-ENDLIST\n");
        return m3u8.toString();
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
            Log.e(TAG, "构建播放结果失败", e);
            return "{\"error\":\"构建播放结果失败: " + e.getMessage() + "\"}";
        }
    }

    public String liveContent(Map<String, String> params) {
        Log.d(TAG, "生成直播内容");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("4GTV,#genre#\n");
            
            Map<String, String> channels = getAllChannels();
            for (Map.Entry<String, String> entry : channels.entrySet()) {
                String channelId = entry.getKey();
                String channelName = getChannelName(channelId);
                String proxyUrl = "proxy://do=gtv&id=" + channelId + "&type=m3u8";
                sb.append(channelName).append(",").append(proxyUrl).append("\n");
            }
            
            // 添加LITV频道
            sb.append("LITV,#genre#\n");
            for (String channelId : LITV_VIDEO_SOUND_MAPPING.keySet()) {
                String channelName = getChannelName(channelId);
                String proxyUrl = "proxy://do=gtv&id=" + channelId + "&type=m3u8";
                sb.append(channelName).append(",").append(proxyUrl).append("\n");
            }
            
            String result = sb.toString();
            Log.d(TAG, "生成的直播内容: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "生成直播内容失败", e);
            return "4GTV,#genre#\n错误," + e.getMessage() + "\n";
        }
    }

    private String getChannelName(String channelId) {
        // 这里可以根据channelId返回对应的频道名称
        // 实际实现可能需要从API获取或使用映射表
        return channelId;
    }

    private String generateUUID(String user) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String today = sdf.format(new Date());
        String name = user + "-" + today;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString().toUpperCase();
    }

    private String generate4GTVAuth() {
        try {
            String headKey = "PyPJU25iI2IQCMWq7kblwh9sGCypqsxMp4sKjJo95SK43h08ff+j1nbWliTySSB+N67BnXrYv9DfwK+ue5wWkg==";
            byte[] key = "ilyB29ZdruuQjC45JhBBR7o2Z8WJ26Vg".getBytes(StandardCharsets.UTF_8);
            byte[] iv = "JUMxvVMmszqUTeKn".getBytes(StandardCharsets.UTF_8);
            
            byte[] decoded = Base64.decode(headKey, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(decoded);
            
            // 移除填充
            int padLen = decrypted[decrypted.length - 1];
            byte[] result = new byte[decrypted.length - padLen];
            System.arraycopy(decrypted, 0, result, 0, result.length);
            
            String decryptedStr = new String(result, StandardCharsets.UTF_8);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
            String today = sdf.format(new Date());
            
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest((today + decryptedStr).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "生成4GTV认证失败", e);
            return "";
        }
    }

    private String signIn4GTV(String user, String password, String fsencKey, String authVal) {
        try {
            String url = "https://api2.4gtv.tv/AppAccount/SignIn";
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            headers.put("fsenc_key", fsencKey);
            headers.put("fsdevice", "iOS");
            headers.put("fsversion", "3.2.8");
            headers.put("4gtv_auth", authVal);
            headers.put("User-Agent", DEFAULT_USER_AGENT);
            
            JSONObject payload = new JSONObject();
            payload.put("fsUSER", user);
            payload.put("fsPASSWORD", password);
            payload.put("fsENC_KEY", fsencKey);
            
            String response = httpPost(url, payload.toString(), headers);
            if (response != null) {
                JSONObject json = new JSONObject(response);
                if (json.optBoolean("Success", false)) {
                    return json.getJSONObject("Data").optString("fsVALUE", "");
                } else {
                    Log.e(TAG, "登录失败，响应: " + response);
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "4GTV登录失败", e);
            return null;
        }
    }

    private Map<String, String> getAllChannels() {
        try {
            String url = "https://api2.4gtv.tv/Channel/GetChannelBySetId/1/pc/L/V";
            
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "*/*");
            headers.put("origin", "https://www.4gtv.tv");
            headers.put("referer", "https://www.4gtv.tv/");
            headers.put("User-Agent", DEFAULT_USER_AGENT);
            
            String response = httpGet(url, headers);
            if (response != null) {
                JSONObject json = new JSONObject(response);
                Map<String, String> channels = new HashMap<>();
                if (json.optBoolean("Success", false)) {
                    JSONArray data = json.getJSONArray("Data");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject channel = data.getJSONObject(i);
                        String channelId = channel.optString("fs4GTV_ID", "");
                        String fnId = channel.optString("fnID", "");
                        if (!channelId.isEmpty() && !fnId.isEmpty()) {
                            channels.put(channelId, fnId);
                        }
                    }
                }
                Log.d(TAG, "获取到频道数量: " + channels.size());
                return channels;
            }
            
            return new HashMap<>();
        } catch (Exception e) {
            Log.e(TAG, "获取频道列表失败", e);
            return new HashMap<>();
        }
    }

    private String get4GTVChannelUrl(String channelId, String fnChannelId, String fsValue, String fsencKey, String authVal) {
        try {
            String url = "https://api2.4gtv.tv/App/GetChannelUrl2";
            
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("fsenc_key", fsencKey);
            headers.put("accept", "*/*");
            headers.put("fsdevice", "iOS");
            headers.put("fsvalue", "");
            headers.put("fsversion", "3.2.8");
            headers.put("4gtv_auth", authVal);
            headers.put("Referer", "https://www.4gtv.tv/");
            headers.put("User-Agent", DEFAULT_USER_AGENT);
            
            JSONObject payload = new JSONObject();
            payload.put("fnCHANNEL_ID", fnChannelId);
            
            JSONObject identity = new JSONObject();
            identity.put("fsVALUE", fsValue);
            identity.put("fsENC_KEY", fsencKey);
            payload.put("clsAPP_IDENTITY_VALIDATE_ARUS", identity);
            
            payload.put("fsASSET_ID", channelId);
            payload.put("fsDEVICE_TYPE", "mobile");
            
            String response = httpPost(url, payload.toString(), headers);
            if (response != null) {
                JSONObject json = new JSONObject(response);
                if (json.optBoolean("Success", false)) {
                    JSONObject data = json.getJSONObject("Data");
                    JSONArray urls = data.getJSONArray("flstURLs");
                    if (urls.length() > 1) {
                        return urls.getString(1);
                    }
                } else {
                    Log.e(TAG, "获取频道URL失败，响应: " + response);
                }
            }
            
            return null;
        } catch (Exception e) {
            Log.e(TAG, "获取4GTV频道URL失败", e);
            return null;
        }
    }

    private String getHighestBitrateUrl(String masterUrl, String fnChannelId) {
        // 这里简化实现，实际应该从API获取最高码率信息
        // 暂时返回原始URL
        return masterUrl;
    }

    // 使用 Java 标准库实现 HTTP GET 请求
    private String httpGet(String urlString, Map<String, String> headers) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT * 1000);
            connection.setReadTimeout(DEFAULT_TIMEOUT * 1000);
            
            // 设置请求头
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
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
            } else {
                Log.e(TAG, "HTTP GET 请求失败，响应码: " + responseCode + ", URL: " + urlString);
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP GET 请求异常", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    // 使用 Java 标准库实现 HTTP POST 请求
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
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            
            // 发送请求体
            byte[] postData = jsonData.getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", Integer.toString(postData.length));
            connection.getOutputStream().write(postData);
            
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
            } else {
                Log.e(TAG, "HTTP POST 请求失败，响应码: " + responseCode + ", URL: " + urlString);
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP POST 请求异常", e);
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
