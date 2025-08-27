package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GTVSpider extends Spider {

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
        try {
            JSONObject json = new JSONObject(extend);
            user = json.optString("user", "");
            password = json.optString("password", "");
            token = json.optString("token", "");
        } catch (JSONException e) {
            user = "";
            password = "";
            token = "";
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return "{}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "{}";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return "{}";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (id.startsWith("proxy://")) {
            return handleProxy(id);
        }
        return "{}";
    }

    private String handleProxy(String url) {
        try {
            Map<String, String> params = parseProxyUrl(url);
            String channelId = params.get("id");
            String type = params.get("type", "m3u8");
            
            if (channelId.startsWith("4gtv-live")) {
                return handle4GTVChannel(channelId, type);
            } else {
                return handleLITVChannel(channelId, type);
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private Map<String, String> parseProxyUrl(String url) {
        Map<String, String> params = new HashMap<>();
        String[] parts = url.split("&");
        for (String part : parts) {
            String[] keyValue = part.split("=");
            if (keyValue.length == 2) {
                params.put(keyValue[0].replace("proxy://do=gtv&", ""), keyValue[1]);
            }
        }
        return params;
    }

    private String handle4GTVChannel(String channelId, String type) throws Exception {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(channelId);
        
        if (entry != null && now - entry.timestamp < CACHE_TTL) {
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
            throw new Exception("频道未找到");
        }
        
        String masterUrl = get4GTVChannelUrl(channelId, fnChannelId, fsValue, fsencKey, authVal);
        if (masterUrl == null || masterUrl.isEmpty()) {
            throw new Exception("无法获取流URL");
        }
        
        String highestUrl = getHighestBitrateUrl(masterUrl, fnChannelId);
        
        cache.put(channelId, new CacheEntry(now, highestUrl));
        
        return buildPlayResult(highestUrl, type);
    }

    private String handleLITVChannel(String channelId, String type) throws Exception {
        if (!LITV_VIDEO_SOUND_MAPPING.containsKey(channelId)) {
            throw new Exception("LITV频道未找到");
        }
        
        if ("ts".equals(type)) {
            return generateLITVTsUrl(channelId);
        } else {
            return generateLITVM3U8(channelId);
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
        return result.toString();
    }

    @Override
    public String liveContent(Map<String, String> params) throws Exception {
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
        
        return sb.toString();
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

    private String generate4GTVAuth() throws Exception {
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
    }

    private String signIn4GTV(String user, String password, String fsencKey, String authVal) throws Exception {
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
        
        String response = OkHttp.string(url, headers, OkHttp.POST, payload.toString());
        JSONObject json = new JSONObject(response);
        
        if (json.optBoolean("Success", false)) {
            return json.getJSONObject("Data").optString("fsVALUE", "");
        }
        
        return null;
    }

    private Map<String, String> getAllChannels() throws Exception {
        String url = "https://api2.4gtv.tv/Channel/GetChannelBySetId/1/pc/L/V";
        
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("origin", "https://www.4gtv.tv");
        headers.put("referer", "https://www.4gtv.tv/");
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        
        String response = OkHttp.string(url, headers);
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
        
        return channels;
    }

    private String get4GTVChannelUrl(String channelId, String fnChannelId, String fsValue, String fsencKey, String authVal) throws Exception {
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
        
        String response = OkHttp.string(url, headers, OkHttp.POST, payload.toString());
        JSONObject json = new JSONObject(response);
        
        if (json.optBoolean("Success", false)) {
            JSONObject data = json.getJSONObject("Data");
            JSONArray urls = data.getJSONArray("flstURLs");
            if (urls.length() > 1) {
                return urls.getString(1);
            }
        }
        
        return null;
    }

    private String getHighestBitrateUrl(String masterUrl, String fnChannelId) throws Exception {
        // 这里简化实现，实际应该从API获取最高码率信息
        // 暂时返回原始URL
        return masterUrl;
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
