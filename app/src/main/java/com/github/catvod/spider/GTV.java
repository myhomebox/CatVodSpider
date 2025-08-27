package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class GTV extends Spider {

    private static final String DEFAULT_USER_AGENT = "四季線上/4 CFNetwork/3826.500.131 Darwin/24.5.0";
    private static final int DEFAULT_TIMEOUT = 30;
    private static final int CHANNEL_DELAY = 3;
    private static final int MAX_RETRIES = 3;
    private static final long CACHE_EXPIRATION_TIME = 86400000; // 24小时

    private String user;
    private String password;
    private String ua;
    private int timeout;
    
    private static Map<String, CacheItem> cachePlayUrls = new ConcurrentHashMap<>();

    @Override
    public void init(Context context, String extend) {
        try {
            JsonObject json = new Gson().fromJson(extend, JsonObject.class);
            user = json.has("user") ? json.get("user").getAsString() : "";
            password = json.has("password") ? json.get("password").getAsString() : "";
            ua = json.has("ua") ? json.get("ua").getAsString() : DEFAULT_USER_AGENT;
            timeout = json.has("timeout") ? json.get("timeout").getAsInt() : DEFAULT_TIMEOUT;
        } catch (Exception e) {
            user = "";
            password = "";
            ua = DEFAULT_USER_AGENT;
            timeout = DEFAULT_TIMEOUT;
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
        return "{}";
    }

    @Override
    public String liveContent() throws Exception {
        if (user.isEmpty() || password.isEmpty()) {
            return "账号或密码未设置";
        }

        String fsencKey = generateUuid(user);
        String authVal = generate4GtvAuth();
        String fsValue = signIn4Gtv(user, password, fsencKey, authVal, ua, timeout);

        if (fsValue == null || fsValue.isEmpty()) {
            return "登录失败";
        }

        List<Channel> channels = getAllChannels(ua, timeout);
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");

        for (Channel channel : channels) {
            if (!channel.fs4GTV_ID.startsWith("4gtv-live")) {
                continue;
            }

            Thread.sleep(CHANNEL_DELAY * 1000);

            String streamUrl = get4GtvChannelUrlWithRetry(
                channel.fs4GTV_ID, 
                channel.fnID, 
                fsValue, 
                fsencKey, 
                authVal, 
                ua, 
                timeout,
                MAX_RETRIES
            );

            if (streamUrl != null && !streamUrl.isEmpty()) {
                String highestUrl = getHighestBitrateUrl(streamUrl);
                sb.append(String.format("#EXTINF:-1 tvg-id=\"%s\" tvg-name=\"%s\" tvg-logo=\"%s\" group-title=\"%s\",%s\n",
                    channel.fsNAME, channel.fsNAME, channel.fsLOGO_MOBILE, channel.fsTYPE_NAME, channel.fsNAME));
                sb.append("proxy://do=gtv&url=").append(URLEncoder.encode(highestUrl, "UTF-8")).append("\n");
            }
        }

        return sb.toString();
    }

    public static Object[] proxy(Map<String, String> params) {
        try {
            String url = params.get("url");
            if (url != null && !url.isEmpty()) {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", DEFAULT_USER_AGENT);
                headers.put("Referer", "https://www.4gtv.tv/");
                headers.put("Origin", "https://www.4gtv.tv/");
                
                String content = OkHttp.string(url, headers);
                return new Object[]{200, "application/vnd.apple.mpegurl", new java.io.ByteArrayInputStream(content.getBytes())};
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String generateUuid(String user) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            String today = sdf.format(new Date());
            String name = user + "-" + today;
            return UUID.nameUUIDFromBytes(name.getBytes()).toString().toUpperCase(Locale.getDefault());
        } catch (Exception e) {
            return UUID.randomUUID().toString().toUpperCase(Locale.getDefault());
        }
    }

    private String generate4GtvAuth() {
        try {
            String headKey = "PyPJU25iI2IQCMWq7kblwh9sGCypqsxMp4sKjJo95SK43h08ff+j1nbWliTySSB+N67BnXrYv9DfwK+ue5wWkg==";
            byte[] KEY = "ilyB29ZdruuQjC45JhBBR7o2Z8WJ26Vg".getBytes("UTF-8");
            byte[] IV = "JUMxvVMmszqUTeKn".getBytes("UTF-8");
            byte[] decoded = Base64.decode(headKey, Base64.DEFAULT);

            SecretKeySpec keySpec = new SecretKeySpec(KEY, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decrypted = cipher.doFinal(decoded);

            // Remove padding
            int padLen = decrypted[decrypted.length - 1] & 0xFF;
            byte[] unpadded = new byte[decrypted.length - padLen];
            System.arraycopy(decrypted, 0, unpadded, 0, unpadded.length);

            String decryptedStr = new String(unpadded, "UTF-8");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
            String today = sdf.format(new Date());
            String toHash = today + decryptedStr;

            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(toHash.getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private String signIn4Gtv(String user, String password, String fsencKey, String authVal, String ua, int timeout) {
        try {
            String url = "https://api2.4gtv.tv/AppAccount/SignIn";
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json; charset=UTF-8");
            headers.put("fsenc_key", fsencKey);
            headers.put("fsdevice", "iOS");
            headers.put("fsversion", "3.2.8");
            headers.put("4gtv_auth", authVal);
            headers.put("User-Agent", ua);

            JsonObject payload = new JsonObject();
            payload.addProperty("fsUSER", user);
            payload.addProperty("fsPASSWORD", password);
            payload.addProperty("fsENC_KEY", fsencKey);

            String result = OkHttp.postJson(url, new Gson().toJson(payload), headers);
            JsonObject json = new Gson().fromJson(result, JsonObject.class);
            if (json != null && json.has("Success") && json.get("Success").getAsBoolean()) {
                return json.getAsJsonObject("Data").get("fsVALUE").getAsString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private List<Channel> getAllChannels(String ua, int timeout) {
        List<Channel> channels = new ArrayList<>();
        try {
            String url = "https://api2.4gtv.tv/Channel/GetChannelBySetId/1/pc/L/V";
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "*/*");
            headers.put("origin", "https://www.4gtv.tv");
            headers.put("referer", "https://www.4gtv.tv/");
            headers.put("User-Agent", ua);

            String result = OkHttp.string(url, headers);
            JsonObject json = new Gson().fromJson(result, JsonObject.class);
            if (json != null && json.has("Success") && json.get("Success").getAsBoolean()) {
                JsonArray data = json.getAsJsonArray("Data");
                for (JsonElement item : data) {
                    JsonObject obj = item.getAsJsonObject();
                    Channel channel = new Channel();
                    channel.fs4GTV_ID = obj.has("fs4GTV_ID") ? obj.get("fs4GTV_ID").getAsString() : "";
                    channel.fsNAME = obj.has("fsNAME") ? obj.get("fsNAME").getAsString() : "";
                    channel.fsTYPE_NAME = obj.has("fsTYPE_NAME") ? obj.get("fsTYPE_NAME").getAsString() : "";
                    channel.fsLOGO_MOBILE = obj.has("fsLOGO_MOBILE") ? obj.get("fsLOGO_MOBILE").getAsString() : "";
                    channel.fnID = obj.has("fnID") ? obj.get("fnID").getAsString() : "";
                    channels.add(channel);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return channels;
    }

    private String get4GtvChannelUrlWithRetry(String channelId, String fnChannelId, String fsValue, 
                                            String fsencKey, String authVal, String ua, 
                                            int timeout, int maxRetries) {
        // Check cache first
        String cacheKey = channelId + "_" + fnChannelId;
        CacheItem cached = cachePlayUrls.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRATION_TIME) {
            return cached.url;
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                String url = "https://api2.4gtv.tv/App/GetChannelUrl2";
                Map<String, String> headers = new HashMap<>();
                headers.put("content-type", "application/json; charset=utf-8");
                headers.put("fsenc_key", fsencKey);
                headers.put("accept", "*/*");
                headers.put("fsdevice", "iOS");
                headers.put("fsvalue", "");
                headers.put("fsversion", "3.2.8");
                headers.put("4gtv_auth", authVal);
                headers.put("Referer", "https://www.4gtv.tv/");
                headers.put("User-Agent", ua);
                headers.put("X-Forwarded-For", "49.159.74.105");

                JsonObject clsApp = new JsonObject();
                clsApp.addProperty("fsVALUE", fsValue);
                clsApp.addProperty("fsENC_KEY", fsencKey);

                JsonObject payload = new JsonObject();
                payload.addProperty("fnCHANNEL_ID", fnChannelId);
                payload.add("clsAPP_IDENTITY_VALIDATE_ARUS", clsApp);
                payload.addProperty("fsASSET_ID", channelId);
                payload.addProperty("fsDEVICE_TYPE", "mobile");

                String result = OkHttp.postJson(url, new Gson().toJson(payload), headers);
                JsonObject json = new Gson().fromJson(result, JsonObject.class);
                if (json != null && json.has("Success") && json.get("Success").getAsBoolean()) {
                    JsonObject data = json.getAsJsonObject("Data");
                    if (data != null && data.has("flstURLs")) {
                        JsonArray urls = data.getAsJsonArray("flstURLs");
                        if (urls.size() > 1) {
                            String streamUrl = urls.get(1).getAsString();
                            // Update cache
                            cachePlayUrls.put(cacheKey, new CacheItem(System.currentTimeMillis(), streamUrl));
                            return streamUrl;
                        }
                    }
                }
            } catch (Exception e) {
                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return "";
    }

    private String getHighestBitrateUrl(String masterUrl) {
        if (masterUrl.contains("index.m3u8")) {
            return masterUrl.replace("index.m3u8", "1080.m3u8");
        }
        return masterUrl;
    }

    static class Channel {
        String fs4GTV_ID;
        String fsNAME;
        String fsTYPE_NAME;
        String fsLOGO_MOBILE;
        String fnID;
    }

    static class CacheItem {
        long timestamp;
        String url;

        CacheItem(long timestamp, String url) {
            this.timestamp = timestamp;
            this.url = url;
        }
    }
}
