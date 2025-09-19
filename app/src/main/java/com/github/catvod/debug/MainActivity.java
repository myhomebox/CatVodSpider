package com.github.catvod.debug;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.github.catvod.R;
import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.Init;
import com.github.catvod.spider.MQiTV;
import com.github.catvod.spider.Uvod;
import com.github.catvod.spider.Proxy;
import com.orhanobut.logger.AndroidLogAdapter;
import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {

    private ExecutorService executor;
    private Spider mqitvSpider;
    private Spider uvodSpider;
    private TextView resultTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 初始化UI組件
        Button homeContent = findViewById(R.id.homeContent);
        Button homeVideoContent = findViewById(R.id.homeVideoContent);
        Button categoryContent = findViewById(R.id.categoryContent);
        Button detailContent = findViewById(R.id.detailContent);
        Button playerContent = findViewById(R.id.playerContent);
        Button searchContent = findViewById(R.id.searchContent);
        Button liveContent = findViewById(R.id.liveContent);
        Button proxy = findViewById(R.id.proxy);
        resultTextView = findViewById(R.id.resultTextView);
        
        // 設置按鈕點擊事件
        homeContent.setOnClickListener(view -> executor.execute(this::homeContent));
        homeVideoContent.setOnClickListener(view -> executor.execute(this::homeVideoContent));
        categoryContent.setOnClickListener(view -> executor.execute(this::categoryContent));
        detailContent.setOnClickListener(view -> executor.execute(this::detailContent));
        playerContent.setOnClickListener(view -> executor.execute(this::playerContent));
        searchContent.setOnClickListener(view -> executor.execute(this::searchContent));
        liveContent.setOnClickListener(view -> executor.execute(this::liveContent));
        proxy.setOnClickListener(view -> executor.execute(this::proxy));
        
        // 初始化日志和線程池
        Logger.addLogAdapter(new AndroidLogAdapter());
        executor = Executors.newCachedThreadPool();
        
        // 初始化爬蟲
        executor.execute(this::initSpiders);
    }

    private void initSpiders() {
        try {
            Init.init(getApplicationContext());
            
            // 初始化 MQiTV 爬蟲
            mqitvSpider = new MQiTV();
            mqitvSpider.init(this, "");
            Logger.t("Spider").d("MQiTV 爬蟲初始化完成");
            
            // 初始化 Uvod 爬蟲
            uvodSpider = new Uvod();
            uvodSpider.init(this, "");
            Logger.t("Spider").d("Uvod 爬蟲初始化完成");
            
            runOnUiThread(() -> resultTextView.setText("兩個爬蟲初始化完成，可以開始測試"));
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("爬蟲初始化失敗: " + e.getMessage()));
        }
    }

    private void updateUI(String methodName, String mqitvResult, String uvodResult) {
        runOnUiThread(() -> {
            String result = "方法: " + methodName + "\n\n" +
                           "MQiTV 結果:\n" + mqitvResult + "\n\n" +
                           "Uvod 結果:\n" + uvodResult;
            resultTextView.setText(result);
        });
    }

    public void homeContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> mqitvSpider.homeContent(true));
            Future<String> uvodFuture = executor.submit(() -> uvodSpider.homeContent(true));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("homeContent-MQiTV").d(mqitvResult);
            Logger.t("homeContent-Uvod").d(uvodResult);
            updateUI("homeContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("homeContent 執行失敗: " + e.getMessage()));
        }
    }

    public void homeVideoContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> mqitvSpider.homeVideoContent());
            Future<String> uvodFuture = executor.submit(() -> uvodSpider.homeVideoContent());
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("homeVideoContent-MQiTV").d(mqitvResult);
            Logger.t("homeVideoContent-Uvod").d(uvodResult);
            updateUI("homeVideoContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("homeVideoContent 執行失敗: " + e.getMessage()));
        }
    }

    public void categoryContent() {
        try {
            HashMap<String, String> extend = new HashMap<>();
            extend.put("c", "19");
            extend.put("year", "2024");
            
            Future<String> mqitvFuture = executor.submit(() -> 
                mqitvSpider.categoryContent("3", "2", true, extend));
            Future<String> uvodFuture = executor.submit(() -> 
                uvodSpider.categoryContent("3", "2", true, extend));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("categoryContent-MQiTV").d(mqitvResult);
            Logger.t("categoryContent-Uvod").d(uvodResult);
            updateUI("categoryContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("categoryContent 執行失敗: " + e.getMessage()));
        }
    }

    public void detailContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> 
                mqitvSpider.detailContent(Arrays.asList("78702")));
            Future<String> uvodFuture = executor.submit(() -> 
                uvodSpider.detailContent(Arrays.asList("78702")));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("detailContent-MQiTV").d(mqitvResult);
            Logger.t("detailContent-Uvod").d(uvodResult);
            updateUI("detailContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("detailContent 執行失敗: " + e.getMessage()));
        }
    }

    public void playerContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> 
                mqitvSpider.playerContent("", "382044/1/78", new ArrayList<>()));
            Future<String> uvodFuture = executor.submit(() -> 
                uvodSpider.playerContent("", "382044/1/78", new ArrayList<>()));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("playerContent-MQiTV").d(mqitvResult);
            Logger.t("playerContent-Uvod").d(uvodResult);
            updateUI("playerContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("playerContent 執行失敗: " + e.getMessage()));
        }
    }

    public void searchContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> 
                mqitvSpider.searchContent("我的人間煙火", false));
            Future<String> uvodFuture = executor.submit(() -> 
                uvodSpider.searchContent("我的人間煙火", false));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("searchContent-MQiTV").d(mqitvResult);
            Logger.t("searchContent-Uvod").d(uvodResult);
            updateUI("searchContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("searchContent 執行失敗: " + e.getMessage()));
        }
    }

    public void liveContent() {
        try {
            Future<String> mqitvFuture = executor.submit(() -> mqitvSpider.liveContent(""));
            Future<String> uvodFuture = executor.submit(() -> uvodSpider.liveContent(""));
            
            String mqitvResult = mqitvFuture.get();
            String uvodResult = uvodFuture.get();
            
            Logger.t("liveContent-MQiTV").d(mqitvResult);
            Logger.t("liveContent-Uvod").d(uvodResult);
            updateUI("liveContent", mqitvResult, uvodResult);
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("liveContent 執行失敗: " + e.getMessage()));
        }
    }

    public void proxy() {
        try {
            Map<String, String> params = new HashMap<>();
            String proxyResult = Proxy.proxy(params);
            Logger.t("proxy").d(proxyResult);
            runOnUiThread(() -> resultTextView.setText("Proxy 結果:\n" + proxyResult));
        } catch (Throwable e) {
            e.printStackTrace();
            runOnUiThread(() -> resultTextView.setText("proxy 執行失敗: " + e.getMessage()));
        }
    }
}
