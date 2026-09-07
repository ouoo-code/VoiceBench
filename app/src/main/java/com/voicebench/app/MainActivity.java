package com.voicebench.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.app.DownloadManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A dependency-free test bench for comparing Android TTS engines.
 * The catalog deliberately includes local, offline and cloud providers so a
 * product team can compare options before adding provider-specific adapters.
 */
public class MainActivity extends android.app.Activity {
    private static final String DOWNLOAD_TAG = "VoiceBenchDownload";
    private static final int BG = Color.rgb(247, 247, 251);
    private static final int CARD = Color.WHITE;
    private static final int INK = Color.rgb(31, 30, 43);
    private static final int MUTED = Color.rgb(111, 110, 130);
    private static final int PURPLE = Color.rgb(124, 92, 252);
    private static final int PURPLE_DARK = Color.rgb(87, 60, 205);
    private static final int BORDER = Color.rgb(231, 230, 239);

    private final List<EngineOption> engines = new ArrayList<>();
    private android.content.SharedPreferences preferences;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsInitializing;
    private int ttsGeneration;
    private String pendingSpeechText;
    private boolean populatingSystemEngines;
    private String selectedEngineId = "system";
    private String selectedEnginePackage;
    private String downloadingModelId;
    private int downloadPercent;
    private long activeDownloadId = -1L;
    private String installingModelId;
    private EditText textInput;
    private Spinner systemEngineSpinner;
    private SeekBar rateBar;
    private SeekBar pitchBar;
    private TextView rateValue;
    private TextView pitchValue;
    private TextView selectedLabel;
    private TextView statusLabel;
    private LinearLayout catalogContainer;
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private final Handler downloadHandler = new Handler(Looper.getMainLooper());
    private final Runnable downloadPoller = this::refreshDownloadState;
    private OfflineTts sherpaTts;
    private MediaPlayer mediaPlayer;
    private File generatedAudioFile;
    private String generatedAudioEngineName;
    private boolean customTextActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("voicebench", MODE_PRIVATE);
        selectedEnginePackage = preferences.getString("system_engine", null);
        buildCatalog();
        buildUi();
        restoreDownloadState();
        String preferredTtsPackage = selectedEnginePackage;
        if (!isUsableTtsPackage(preferredTtsPackage)) {
            // No valid in-app choice: let Android honor the engine selected in
            // the device's Text-to-speech settings.
            preferredTtsPackage = null;
            selectedEnginePackage = null;
        }
        initTts(preferredTtsPackage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDownloadState();
    }

    @Override
    protected void onPause() {
        downloadHandler.removeCallbacks(downloadPoller);
        super.onPause();
    }

    private void setPresetText(String text) {
        if (customTextActive) saveCurrentTextAsCustom();
        customTextActive = false;
        textInput.setText(text);
    }

    private void saveCurrentTextAsCustom() {
        if (textInput == null) return;
        String currentText = textInput.getText().toString().trim();
        if (!currentText.isEmpty()) {
            preferences.edit().putString("custom_test_text", currentText).apply();
        }
    }

    private void loadCustomText() {
        String savedText = preferences.getString("custom_test_text", "");
        if (savedText.trim().isEmpty()) {
            Toast.makeText(this, "本地还没有保存的自定义文本", Toast.LENGTH_SHORT).show();
            return;
        }
        textInput.setText(savedText);
        customTextActive = true;
        Toast.makeText(this, "已加载本地自定义文本", Toast.LENGTH_SHORT).show();
    }

    private void buildCatalog() {
        engines.clear();
        engines.add(new EngineOption("system", "系统 TTS", "Android 内置", "离线", "设备自带 · 立即可测", "◉", PURPLE));
        engines.add(new EngineOption("google_tts", "Google TTS", "Google Text-to-Speech", "离线", "设备端 Google 引擎 · 需安装语音数据", "G", Color.rgb(66, 133, 244)));
        engines.add(new EngineOption("local_sherpa_onnx", "Sherpa-ONNX (Piper / VITS)", "本地模型", "离线", "流式 · CPU · 支持 Piper / VITS 模型", "◆", Color.rgb(47, 174, 126)));
        engines.add(new EngineOption("local_piper", "Piper 中文", "本地模型", "离线", "中文 Huayan 音色 · CPU · 离线", "◌", Color.rgb(47, 174, 126)));
        engines.add(new EngineOption("local_vits", "VITS", "本地模型", "离线", "中文音色 · 需要 ONNX / 模型文件", "V", Color.rgb(47, 174, 126)));
        engines.add(new EngineOption("local_kokoro", "Kokoro / MeloTTS", "本地模型", "离线", "自然度高 · 需要模型包", "✦", Color.rgb(47, 174, 126)));
        engines.add(new EngineOption("openai", "OpenAI TTS", "云端 API", "API Key", "音色自然 · voices / model 可配", "◎", Color.rgb(241, 131, 76)));
        engines.add(new EngineOption("azure", "Azure Speech", "云端 API", "API Key", "SSML · 音色丰富 · 区域可配", "A", Color.rgb(45, 137, 239)));
        engines.add(new EngineOption("google", "Google Cloud TTS", "云端 API", "API Key / JSON", "WaveNet / Neural2 · 多语言", "G", Color.rgb(66, 133, 244)));
        engines.add(new EngineOption("elevenlabs", "ElevenLabs", "云端 API", "API Key", "表现力强 · voice id 可配", "11", Color.rgb(40, 40, 40)));
        engines.add(new EngineOption("amazon", "Amazon Polly", "云端 API", "Access Key", "标准 / Neural · AWS 区域", "P", Color.rgb(255, 153, 0)));
        engines.add(new EngineOption("aliyun", "阿里云 TTS", "云端 API", "Access Key / API Key", "CosyVoice · 百炼 / DashScope · 中文", "阿", Color.rgb(255, 105, 0)));
        engines.add(new EngineOption("tencent", "腾讯云 TTS", "云端 API", "Secret ID", "中文 · 情感音色", "腾", Color.rgb(44, 154, 235)));
        engines.add(new EngineOption("baidu", "百度智能云", "云端 API", "API Key", "中文 · 精品音库", "度", Color.rgb(43, 106, 223)));
        engines.add(new EngineOption("minimax", "MiniMax", "云端 API", "API Key", "中文 · 高表现力音色", "M", Color.rgb(123, 82, 237)));
        engines.add(new EngineOption("volcengine", "火山引擎", "云端 API", "Access Key", "中文 · 豆包语音", "火", Color.rgb(239, 74, 80)));
        engines.add(new EngineOption("custom", "自定义 REST", "云端 API", "自定义", "Endpoint · Header · Body 可配", "＋", Color.rgb(108, 106, 126)));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(root);

        TextView eyebrow = label("VOICEBENCH  /  TTS LAB", 12, MUTED);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(eyebrow, lp(-1, -2, 0, 0, 0, 8));
        TextView title = label("自然语言合成\n引擎实验台", 30, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, lp(-1, -2, 0, 0, 0, 5));
        TextView subtitle = label("同一段文本，快速对比本地、离线与云端声音。", 14, MUTED);
        root.addView(subtitle, lp(-1, -2, 0, 0, 0, 20));

        LinearLayout inputCard = card();
        inputCard.addView(label("测试文本", 12, MUTED), lp(-1, -2, 0, 0, 0, 8));
        textInput = new EditText(this);
        String defaultText = "你好，欢迎来到 VoiceBench。让我们比较不同语音合成引擎的自然度、速度和表现力。\nHello, this is a voice engine test.";
        String savedCustomText = preferences.getString("custom_test_text", "");
        textInput.setText(savedCustomText.isEmpty() ? defaultText : savedCustomText);
        customTextActive = !savedCustomText.trim().isEmpty();
        textInput.setTextColor(INK);
        textInput.setTextSize(16);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setMinLines(5);
        textInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        textInput.setBackground(round(Color.rgb(249, 249, 253), 12, BORDER));
        inputCard.addView(textInput, lp(-1, -2, 0, 0, 0, 14));
        LinearLayout quick = row();
        quick.addView(pillButton("中文", v -> setPresetText("这是一个中文语音合成测试，重点关注发音、停顿和情感。")), lp(0, -2, 1f, 0, 0, 0, 0));
        quick.addView(space(dp(6)), lp(dp(6), 1));
        quick.addView(pillButton("英文", v -> setPresetText("This is an English voice synthesis test. Please compare clarity, rhythm, and naturalness.")), lp(0, -2, 1f, 0, 0, 0, 0));
        quick.addView(space(dp(6)), lp(dp(6), 1));
        quick.addView(pillButton("混合", v -> setPresetText("中英文混合语音测试。This is a mixed Chinese and English sentence.")), lp(0, -2, 1f, 0, 0, 0, 0));
        quick.addView(space(dp(6)), lp(dp(6), 1));
        quick.addView(pillButton("自定义", v -> loadCustomText()), lp(0, -2, 1f, 0, 0, 0, 0));
        inputCard.addView(quick);
        root.addView(inputCard, lp(-1, -2, 0, 0, 0, 14));

        LinearLayout selectedCard = card();
        LinearLayout selectedTop = row();
        selectedLabel = label("◉  系统 TTS", 17, INK);
        selectedLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        selectedTop.addView(selectedLabel, lp(0, -2, 1f, 0, 0, 0, 0));
        TextView ready = label("已选", 12, PURPLE_DARK);
        ready.setGravity(Gravity.CENTER);
        ready.setBackground(round(Color.rgb(239, 235, 255), 20, Color.TRANSPARENT));
        selectedTop.addView(ready, lp(dp(54), dp(30), 0, 0, 0, 0));
        selectedCard.addView(selectedTop, lp(-1, -2, 0, 0, 0, 12));
        selectedCard.addView(label("系统引擎会直接使用设备已安装的离线语音。云端引擎需要先完成配置。", 13, MUTED), lp(-1, -2, 0, 0, 0, 12));
        statusLabel = label("正在初始化系统 TTS…", 12, MUTED);
        selectedCard.addView(statusLabel, lp(-1, -2, 0, 0, 0, 10));
        LinearLayout actionRow = row();
        Button synthesize = primaryButton("合成");
        synthesize.setOnClickListener(v -> synthesize());
        Button play = primaryButton("播放");
        play.setOnClickListener(v -> playSynthesized());
        actionRow.addView(synthesize, lp(0, dp(50), 1f, 0, 0, 0, 0));
        actionRow.addView(space(dp(8)), lp(dp(8), 1));
        actionRow.addView(play, lp(0, dp(50), 1f, 0, 0, 0, 0));
        selectedCard.addView(actionRow, lp(-1, dp(50), 0, 0, 0, 0));
        root.addView(selectedCard, lp(-1, -2, 0, 0, 0, 14));

        LinearLayout controls = card();
        controls.addView(label("播放参数", 16, INK), lp(-1, -2, 0, 0, 0, 14));
        LinearLayout engineRow = row();
        engineRow.addView(label("系统引擎", 13, MUTED), lp(0, -2, 1f, 0, 0, 0, 0));
        systemEngineSpinner = new Spinner(this);
        systemEngineSpinner.setVisibility(View.GONE);
        engineRow.addView(systemEngineSpinner, lp(0, dp(42), 2f, 0, 0, 0, 0));
        controls.addView(engineRow, lp(-1, dp(44), 0, 0, 0, 6));
        controls.addView(sliderRow("语速", "1.0×", true), lp(-1, -2, 0, 0, 0, 2));
        controls.addView(sliderRow("音调", "1.0×", false), lp(-1, -2, 0, 0, 0, 0));
        root.addView(controls, lp(-1, -2, 0, 0, 0, 20));

        LinearLayout catalogTitle = row();
        TextView catalogText = label("引擎目录", 20, INK);
        catalogText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        catalogTitle.addView(catalogText, lp(0, -2, 1f, 0, 0, 0, 0));
        catalogTitle.addView(label(engines.size() + " 个选项", 12, MUTED), lp(-2, -2, 0, 0, 0, 0));
        root.addView(catalogTitle, lp(-1, -2, 0, 0, 0, 10));
        TextView hint = label("点击卡片选择；云端项目可配置 Key、Endpoint、模型与音色。", 13, MUTED);
        root.addView(hint, lp(-1, -2, 0, 0, 0, 12));

        catalogContainer = new LinearLayout(this);
        catalogContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalogContainer, lp(-1, -2, 0, 0, 0, 0));
        renderCatalog();
        setContentView(scroll);
    }

    private LinearLayout sliderRow(String name, String value, boolean rate) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = row();
        top.addView(label(name, 13, MUTED), lp(0, -2, 1f, 0, 0, 0, 0));
        TextView valueView = label(value, 13, INK);
        top.addView(valueView);
        box.addView(top, lp(-1, -2, 0, 0, 0, 0));
        SeekBar seek = new SeekBar(this);
        seek.setMax(20);
        seek.setProgress(10);
        seek.setContentDescription(name);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float factor = 0.5f + progress * 0.075f;
                valueView.setText(String.format(Locale.US, "%.2f×", factor));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        if (rate) { rateBar = seek; rateValue = valueView; } else { pitchBar = seek; pitchValue = valueView; }
        box.addView(seek, lp(-1, dp(38), 0, 0, 0, 0));
        return box;
    }

    private void renderCatalog() {
        catalogContainer.removeAllViews();
        for (EngineOption option : engines) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(13), dp(10), dp(13));
            boolean selected = option.id.equals(selectedEngineId);
            item.setBackground(round(selected ? Color.rgb(244, 241, 255) : CARD, 16, selected ? Color.rgb(205, 195, 255) : BORDER));
            TextView icon = label(option.icon, 16, Color.WHITE);
            icon.setGravity(Gravity.CENTER);
            icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            icon.setBackground(round(option.color, 12, Color.TRANSPARENT));
            item.addView(icon, lp(dp(40), dp(40), 0, 0, dp(12), 0));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout nameRow = row();
            TextView name = label(option.name, 15, INK);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            nameRow.addView(name, lp(0, -2, 1f, 0, 0, 0, 0));
            TextView kind = label(option.kind, 11, MUTED);
            kind.setGravity(Gravity.CENTER);
            kind.setPadding(dp(7), 0, dp(7), 0);
            kind.setBackground(round(Color.rgb(246, 246, 250), 12, Color.TRANSPARENT));
            nameRow.addView(kind, lp(-2, dp(24), 0, 0, 0, 0));
            body.addView(nameRow);
            body.addView(label(option.note, 12, MUTED), lp(-1, -2, 0, 3, 0, 0));
            item.addView(body, lp(0, -2, 1f, 0, 0, 0, 0));
            boolean localModel = option.kind.equals("本地模型");
            boolean installed = localModel && isModelInstalled(option.id);
            String actionText;
            if (option.id.equals("system")) actionText = "使用";
            else if (localModel && option.id.equals(downloadingModelId)) actionText = "下载 " + downloadPercent + "%";
            else if (localModel && installed) actionText = "已安装";
            else if (localModel) actionText = "下载";
            else actionText = "配置";
            TextView action = label(actionText, 12, selected ? PURPLE_DARK : MUTED);
            action.setGravity(Gravity.CENTER);
            action.setBackground(round(selected ? Color.rgb(231, 224, 255) : Color.rgb(248, 248, 251), 12, Color.TRANSPARENT));
            action.setContentDescription(actionText + " " + option.name);
            item.addView(action, lp(dp(52), dp(34), 0, 0, 0, 0));
            item.setOnClickListener(v -> selectEngine(option));
            if (localModel) action.setOnClickListener(v -> {
                if (installed) selectEngine(option);
                else downloadModel(option);
            });
            catalogContainer.addView(item, lp(-1, -2, 0, 0, 0, 8));
        }
    }

    private void selectEngine(EngineOption option) {
        selectedEngineId = option.id;
        selectedLabel.setText(option.icon + "  " + option.name);
        if (option.id.equals("system")) {
            statusLabel.setText(ttsReady ? "系统 TTS 已就绪 · 离线播放" : "正在等待系统 TTS 初始化…");
            systemEngineSpinner.setVisibility(View.VISIBLE);
            Toast.makeText(this, "已选择系统 TTS", Toast.LENGTH_SHORT).show();
        } else if (option.id.equals("google_tts")) {
            systemEngineSpinner.setVisibility(View.VISIBLE);
            String googlePackage = findGoogleTtsPackage();
            if (googlePackage == null) {
                statusLabel.setText("未检测到 Google Text-to-Speech，请先安装或更新语音数据");
                Toast.makeText(this, "设备未安装 Google TTS 引擎", Toast.LENGTH_LONG).show();
            } else {
                statusLabel.setText("正在切换到 Google Text-to-Speech…");
                if (!googlePackage.equals(selectedEnginePackage)) switchTts(googlePackage);
                else statusLabel.setText("Google Text-to-Speech 已就绪 · 离线播放");
            }
        } else if (option.kind.equals("本地模型")) {
            systemEngineSpinner.setVisibility(View.GONE);
            if (isModelInstalled(option.id)) {
                statusLabel.setText(option.name + " 已安装 · 本地离线推理");
                Toast.makeText(this, "已选择 " + option.name, Toast.LENGTH_SHORT).show();
            } else {
                statusLabel.setText(option.name + " · 点击右侧“下载”安装模型");
                Toast.makeText(this, "请点击右侧“下载”安装模型", Toast.LENGTH_LONG).show();
            }
        } else {
            systemEngineSpinner.setVisibility(View.GONE);
            statusLabel.setText(option.kind + " · 配置后可接入合成请求");
            showProviderDialog(option);
        }
        renderCatalog();
    }

    private void showProviderDialog(EngineOption option) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(18), dp(20), dp(6));

        LinearLayout header = row();
        TextView icon = label(option.icon, 21, Color.WHITE);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        icon.setBackground(round(option.color, 14, Color.TRANSPARENT));
        header.addView(icon, lp(dp(48), dp(48), 0, 0, 0, 14));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView overline = label("配置引擎", 11, PURPLE_DARK);
        overline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(overline);
        TextView title = label(option.name, 22, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title, lp(-1, -2, 0, 2, 0, 0));
        heading.addView(label(option.kind + "  ·  " + option.access, 12, MUTED), lp(-1, -2, 0, 3, 0, 0));
        header.addView(heading, lp(0, -2, 1f, 0, 0, 0, 0));
        form.addView(header, lp(-1, -2, 0, 0, 0, 8));
        form.addView(label("配置保存在本机，仅用于此测试台。", 12, MUTED), lp(-1, -2, 0, 0, 0, 14));

        form.addView(sectionLabel("认证信息"), lp(-1, -2, 0, 0, 0, 5));
        EditText key = addConfigField(form, "API Key / Access Key", "粘贴密钥（本机保存）", option.id + "_key", true);
        EditText secret = addConfigField(form,
                option.id.equals("custom") ? "Secret / Header" : "Secret / Region",
                "可选", option.id + "_secret", false);
        form.addView(sectionLabel("服务参数"), lp(-1, -2, 0, dp(7), 0, 5));
        EditText endpoint = addConfigField(form, "Endpoint", "留空使用默认地址", option.id + "_endpoint", false);
        EditText model = addConfigField(form, "模型 / Voice ID", "例如：中文音色或 voice id", option.id + "_model", false);
        form.addView(sectionLabel("输出"), lp(-1, -2, 0, dp(7), 0, 5));
        EditText format = addConfigField(form, "输出格式", "mp3 / wav", option.id + "_format", false);
        scroll.addView(form);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存配置", null)
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                int width = Math.min(dp(460), getResources().getDisplayMetrics().widthPixels - dp(28));
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                preferences.edit()
                        .putString(option.id + "_key", key.getText().toString())
                        .putString(option.id + "_secret", secret.getText().toString())
                        .putString(option.id + "_endpoint", endpoint.getText().toString())
                        .putString(option.id + "_model", model.getText().toString())
                        .putString(option.id + "_format", format.getText().toString())
                        .apply();
                selectedEngineId = option.id;
                selectedLabel.setText(option.icon + "  " + option.name);
                statusLabel.setText("配置已保存 · 可接入云端适配器");
                renderCatalog();
                Toast.makeText(this, option.name + " 配置已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private TextView sectionLabel(String text) {
        TextView section = label(text, 12, PURPLE_DARK);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return section;
    }

    private EditText addConfigField(LinearLayout parent, String title, String hint, String key, boolean password) {
        TextView fieldTitle = label(title, 12, MUTED);
        fieldTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(fieldTitle, lp(-1, -2, 0, 0, 0, 4));
        EditText input = field(hint, key, password);
        parent.addView(input, lp(-1, dp(48), 0, 0, 0, 8));
        return input;
    }

    private EditText field(String hint, String key, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(preferences.getString(key, ""));
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(dp(13), 0, dp(13), 0);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setBackground(round(Color.rgb(249, 249, 253), 10, BORDER));
        if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void initTts(String enginePackage) {
        final int generation = ++ttsGeneration;
        ttsReady = false;
        ttsInitializing = true;
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        statusLabel.setText("正在初始化系统 TTS…");
        TextToSpeech.OnInitListener listener = result -> onTtsReady(result, generation, enginePackage);
        try {
            if (enginePackage == null || enginePackage.trim().isEmpty()) {
                tts = new TextToSpeech(this, listener);
            } else {
                tts = new TextToSpeech(this, listener, enginePackage);
            }
        } catch (Exception e) {
            if (generation == ttsGeneration && enginePackage != null) {
                retryPreferredTts(enginePackage);
            } else if (generation == ttsGeneration) {
                ttsInitializing = false;
                statusLabel.setText("系统 TTS 初始化失败，请检查系统语音服务");
            }
        }
    }

    private void onTtsReady(int result, int generation, String requestedEnginePackage) {
        if (generation != ttsGeneration) return;
        ttsInitializing = false;
        ttsReady = result == TextToSpeech.SUCCESS;
        if (!ttsReady || tts == null) {
            // A previously selected package may have been removed or disabled.
            // Fall back to Android's default engine instead of leaving the app stuck.
            if (requestedEnginePackage != null && !requestedEnginePackage.trim().isEmpty()) {
                retryPreferredTts(requestedEnginePackage);
            } else {
                ttsReady = false;
                statusLabel.setText("正在重试系统 TTS 初始化…");
            }
            return;
        }
        int languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        if (languageResult == TextToSpeech.LANG_MISSING_DATA
                || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
            statusLabel.setText("系统 TTS 已连接 · 使用系统默认语言");
        } else {
            statusLabel.setText("系统 TTS 已就绪 · 离线播放");
        }
        if (requestedEnginePackage == null && selectedEnginePackage == null) {
            String actualDefault = tts.getDefaultEngine();
            if (actualDefault != null && !actualDefault.trim().isEmpty()) {
                selectedEnginePackage = actualDefault;
            }
        }
        runOnUiThread(this::populateSystemEngines);
        if (pendingSpeechText != null) {
            String text = pendingSpeechText;
            pendingSpeechText = null;
            runOnUiThread(() -> speakWithSystem(text));
        }
    }

    private void populateSystemEngines() {
        if (systemEngineSpinner == null || tts == null) return;
        List<TextToSpeech.EngineInfo> infos = new ArrayList<>();
        for (TextToSpeech.EngineInfo info : tts.getEngines()) {
            // Keep every engine reported by Android, including Xiaomi's
            // system engine. MIUI may still reject the service at bind time;
            // that case is handled by the normal initialization fallback.
            infos.add(info);
        }
        List<String> labels = new ArrayList<>();
        for (TextToSpeech.EngineInfo info : infos) labels.add(info.label + "  ·  " + info.name);
        if (labels.isEmpty()) labels.add("系统默认引擎");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        systemEngineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (populatingSystemEngines) return;
                if (position < infos.size() && infos.get(position).name != null && !infos.get(position).name.equals(selectedEnginePackage)) {
                    selectedEnginePackage = infos.get(position).name;
                    preferences.edit().putString("system_engine", selectedEnginePackage).apply();
                    switchTts(selectedEnginePackage);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        int selectedIndex = 0;
        if (selectedEnginePackage != null) {
            for (int i = 0; i < infos.size(); i++) {
                if (selectedEnginePackage.equals(infos.get(i).name)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        // Setting the adapter/selection is a UI refresh, not a user request to
        // tear down and recreate the TTS engine. Only a real user selection
        // should call switchTts().
        populatingSystemEngines = true;
        systemEngineSpinner.setAdapter(adapter);
        systemEngineSpinner.setVisibility(View.VISIBLE);
        if (selectedIndex < labels.size()) systemEngineSpinner.setSelection(selectedIndex);
        systemEngineSpinner.post(() -> populatingSystemEngines = false);
    }

    private String findGoogleTtsPackage() {
        for (String packageName : discoverTtsPackages()) {
            String name = packageName.toLowerCase(Locale.US);
            if (name.contains("google") || name.equals("com.google.android.tts")) return packageName;
        }
        return null;
    }

    private List<String> discoverTtsPackages() {
        List<String> packages = new ArrayList<>();
        Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, PackageManager.MATCH_ALL);
        for (ResolveInfo service : services) {
            if (service.serviceInfo != null && service.serviceInfo.packageName != null) {
                packages.add(service.serviceInfo.packageName);
            }
        }
        return packages;
    }

    private boolean isUsableTtsPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        return discoverTtsPackages().contains(packageName);
    }

    private String findPreferredTtsPackage() {
        List<String> packages = discoverTtsPackages();
        for (String packageName : packages) {
            if (packageName.equalsIgnoreCase("com.google.android.tts")) return packageName;
        }
        for (String packageName : packages) {
            return packageName;
        }
        return null;
    }

    private void retryPreferredTts(String failedPackage) {
        selectedEnginePackage = null;
        preferences.edit().remove("system_engine").apply();
        String fallback = findPreferredTtsPackage();
        if (fallback != null && !fallback.equalsIgnoreCase(failedPackage)) {
            initTts(fallback);
        } else {
            ttsInitializing = false;
            ttsReady = false;
            statusLabel.setText("系统 TTS 被系统限制，请安装或启用 Google TTS");
        }
    }

    private void switchTts(String packageName) {
        initTts(packageName);
    }

    private void synthesize() {
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) { Toast.makeText(this, "请先输入测试文本", Toast.LENGTH_SHORT).show(); return; }
        if (customTextActive) saveCurrentTextAsCustom();
        if (isLocalEngine(selectedEngineId)) {
            if (!isModelInstalled(selectedEngineId)) {
                Toast.makeText(this, "请先下载并安装本地模型", Toast.LENGTH_LONG).show();
                return;
            }
            synthesizeWithSherpa(text, selectedEngineId);
            return;
        }
        if (!selectedEngineId.equals("system")) {
            statusLabel.setText("云端配置已就绪 · 点击播放使用系统 TTS 基准");
            return;
        }
        statusLabel.setText("系统 TTS 已准备 · 请点击播放");
    }

    private void playSynthesized() {
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请先输入测试文本", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isLocalEngine(selectedEngineId)) {
            if (generatedAudioFile == null || !generatedAudioFile.isFile()) {
                Toast.makeText(this, "请先点击合成", Toast.LENGTH_SHORT).show();
                return;
            }
            playGeneratedAudio(generatedAudioFile,
                    generatedAudioEngineName == null ? selectedEngineId : generatedAudioEngineName);
        } else {
            speakWithSystem(text);
        }
    }

    private void speakWithSystem(String text) {
        if (!ttsReady || tts == null) {
            pendingSpeechText = text;
            if (!ttsInitializing) initTts(findPreferredTtsPackage());
            Toast.makeText(this, "系统 TTS 正在初始化，完成后自动播放", Toast.LENGTH_SHORT).show();
            return;
        }
        float rate = 0.5f + rateBar.getProgress() * 0.075f;
        float pitch = 0.5f + pitchBar.getProgress() * 0.075f;
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voicebench_preview");
        statusLabel.setText("播放中 · " + String.format(Locale.US, "语速 %.2f× / 音调 %.2f×", rate, pitch));
    }

    private boolean isLocalEngine(String id) {
        return id != null && (id.equals("local_sherpa_onnx") || id.equals("local_piper")
                || id.equals("local_vits") || id.equals("local_kokoro"));
    }

    private ModelSpec modelSpecFor(String id) {
        if (id.equals("local_kokoro")) {
            return new ModelSpec(id, "Kokoro 多语言", "kokoro-multi-lang-v1_0.tar.bz2",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2", true);
        }
        if (id.equals("local_piper")) {
            return new ModelSpec(id, "Piper 中文", "vits-piper-zh_CN-huayan-medium.tar.bz2",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-huayan-medium.tar.bz2", false);
        }
        if (id.equals("local_vits")) {
            return new ModelSpec(id, "VITS 中文", "sherpa-onnx-vits-zh-ll.tar.bz2",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-vits-zh-ll.tar.bz2", false);
        }
        return new ModelSpec(id, "Sherpa-ONNX 中文/英文", "vits-melo-tts-zh_en.tar.bz2",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2", false);
    }

    private File modelDirectory(String id) {
        return new File(new File(getFilesDir(), "models"), id);
    }

    private boolean isModelInstalled(String id) {
        File directory = modelDirectory(id);
        if (id.equals("local_piper") && !modelVersionFor(id).equals(
                preferences.getString("model_version_" + id, ""))) return false;
        return directory.isDirectory() && findFirstFile(directory, ".onnx") != null;
    }

    private String modelVersionFor(String id) {
        return id.equals("local_piper") ? "vits-piper-zh_CN-huayan-medium" : "1";
    }

    private void downloadModel(EngineOption option) {
        if (downloadingModelId != null) {
            statusLabel.setText("正在下载模型，请稍候… " + downloadPercent + "%");
            return;
        }
        ModelSpec spec = modelSpecFor(option.id);
        downloadingModelId = option.id;
        downloadPercent = 0;
        statusLabel.setText("开始下载 " + spec.displayName + "…");
        renderCatalog();
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(spec.url));
        request.setTitle(spec.displayName);
        request.setDescription("VoiceBench 本地语音模型");
        request.setMimeType("application/x-bzip2");
        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, spec.id + ".tar.bz2");
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            resetDownloadUi("系统下载服务不可用");
            return;
        }
        try {
            activeDownloadId = manager.enqueue(request);
            preferences.edit().putLong("download_id", activeDownloadId)
                    .putString("download_model", spec.id).apply();
            Log.i(DOWNLOAD_TAG, "queued by DownloadManager id=" + activeDownloadId
                    + ", model=" + spec.id + ", url=" + spec.url);
            refreshDownloadState();
        } catch (RuntimeException error) {
            Log.e(DOWNLOAD_TAG, "enqueue failed model=" + spec.id, error);
            resetDownloadUi("无法启动系统下载：" + error.getMessage());
        }
    }

    private void restoreDownloadState() {
        activeDownloadId = preferences.getLong("download_id", -1L);
        downloadingModelId = preferences.getString("download_model", null);
        if (activeDownloadId > 0 && downloadingModelId != null) {
            Log.i(DOWNLOAD_TAG, "restore download id=" + activeDownloadId + ", model=" + downloadingModelId);
            statusLabel.setText("正在恢复系统下载…");
            renderCatalog();
        } else {
            activeDownloadId = -1L;
            downloadingModelId = null;
        }
    }

    private void refreshDownloadState() {
        if (activeDownloadId <= 0 || downloadingModelId == null || installingModelId != null) return;
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
        try (android.database.Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                resetDownloadUi("系统下载任务不存在");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            downloadPercent = total > 0 ? (int) Math.min(99, downloaded * 100L / total) : 0;
            Log.i(DOWNLOAD_TAG, "DownloadManager id=" + activeDownloadId + ", status=" + status
                    + ", bytes=" + downloaded + "/" + total + ", percent=" + downloadPercent);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String modelId = downloadingModelId;
                File archive = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), modelId + ".tar.bz2");
                beginInstall(modelId, archive);
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                Log.e(DOWNLOAD_TAG, "DownloadManager failed id=" + activeDownloadId + ", reason=" + reason);
                resetDownloadUi("系统下载失败，错误码 " + reason);
            } else {
                statusLabel.setText("系统下载中… " + downloadPercent + "%");
                renderCatalog();
                downloadHandler.removeCallbacks(downloadPoller);
                downloadHandler.postDelayed(downloadPoller, 1000L);
            }
        }
    }

    private void beginInstall(String modelId, File archive) {
        if (installingModelId != null) return;
        installingModelId = modelId;
        EngineOption option = findEngineOption(modelId);
        ModelSpec spec = modelSpecFor(modelId);
        statusLabel.setText("下载完成，正在安装 " + spec.displayName + "…");
        renderCatalog();
        modelExecutor.execute(() -> {
            File installDir = modelDirectory(modelId);
            try {
                if (!archive.isFile() || archive.length() == 0) throw new IOException("系统下载文件不存在或为空");
                Log.i(DOWNLOAD_TAG, "install start model=" + modelId + ", archive=" + archive.getAbsolutePath()
                        + ", bytes=" + archive.length());
                deleteRecursively(installDir);
                if (!installDir.mkdirs() && !installDir.isDirectory()) throw new IOException("无法创建模型目录");
                extractTarBz2(archive, installDir);
                if (findFirstFile(installDir, ".onnx") == null) throw new IOException("模型包中没有 ONNX 文件");
                preferences.edit().putBoolean("model_" + modelId, true)
                        .putString("model_version_" + modelId, modelVersionFor(modelId))
                        .remove("download_id").remove("download_model").apply();
                archive.delete();
                Log.i(DOWNLOAD_TAG, "install complete model=" + modelId + ", directory=" + installDir.getAbsolutePath());
                runOnUiThread(() -> {
                    selectedEngineId = modelId;
                    selectedLabel.setText(option.icon + "  " + option.name);
                    statusLabel.setText(option.name + " 已安装并生效 · 本地离线推理");
                    activeDownloadId = -1L;
                    downloadingModelId = null;
                    installingModelId = null;
                    downloadPercent = 100;
                    renderCatalog();
                    Toast.makeText(this, option.name + " 下载完成", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                Log.e(DOWNLOAD_TAG, "install failed model=" + modelId + ", message=" + error.getMessage(), error);
                archive.delete();
                deleteRecursively(installDir);
                runOnUiThread(() -> {
                    installingModelId = null;
                    resetDownloadUi("模型安装失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
                });
            }
        });
    }

    private EngineOption findEngineOption(String id) {
        for (EngineOption option : engines) if (option.id.equals(id)) return option;
        return new EngineOption(id, id, "本地模型", "离线", "本地模型", "◆", Color.rgb(47, 174, 126));
    }

    private void resetDownloadUi(String message) {
        preferences.edit().remove("download_id").remove("download_model").apply();
        activeDownloadId = -1L;
        downloadingModelId = null;
        installingModelId = null;
        downloadPercent = 0;
        statusLabel.setText(message);
        renderCatalog();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void extractTarBz2(File archive, File destination) throws IOException {
        String destinationPath = destination.getCanonicalPath() + File.separator;
        try (InputStream fileInput = new BufferedInputStream(new FileInputStream(archive));
             BZip2CompressorInputStream bz2 = new BZip2CompressorInputStream(fileInput);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz2)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = tar.getNextTarEntry()) != null) {
                File output = new File(destination, entry.getName()).getCanonicalFile();
                if (!output.getPath().startsWith(destinationPath)) throw new IOException("非法模型路径");
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) throw new IOException("无法创建模型目录");
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建模型目录");
                try (OutputStream fileOutput = new BufferedOutputStream(new FileOutputStream(output))) {
                    int count;
                    while ((count = tar.read(buffer)) != -1) fileOutput.write(buffer, 0, count);
                }
            }
        }
    }

    private File findFirstFile(File root, String suffix) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findFirstFile(file, suffix);
                if (found != null) return found;
            } else if (file.getName().toLowerCase(Locale.US).endsWith(suffix.toLowerCase(Locale.US))) {
                return file;
            }
        }
        return null;
    }

    private File findNamedFile(File root, String fileName) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findNamedFile(file, fileName);
                if (found != null) return found;
            } else if (file.getName().equalsIgnoreCase(fileName)) {
                return file;
            }
        }
        return null;
    }

    private File findDirectory(File root, String directoryName) {
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory() && file.getName().equalsIgnoreCase(directoryName)) return file;
            if (file.isDirectory()) {
                File found = findDirectory(file, directoryName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void synthesizeWithSherpa(String text, String engineId) {
        float rate = 0.5f + rateBar.getProgress() * 0.075f;
        ModelSpec spec = modelSpecFor(engineId);
        statusLabel.setText("正在生成本地语音…");
        modelExecutor.execute(() -> {
            try {
                File root = modelDirectory(engineId);
                File model = findFirstFile(root, ".onnx");
                File tokens = findNamedFile(root, "tokens.txt");
                File dataDir = findDirectory(root, "espeak-ng-data");
                if (model == null || tokens == null) throw new IOException("模型文件不完整");
                OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
                modelConfig.setNumThreads(2);
                modelConfig.setProvider("cpu");
                if (spec.kokoro) {
                    File voices = findNamedFile(root, "voices.bin");
                    if (voices == null) throw new IOException("Kokoro 模型缺少 voices.bin");
                    OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
                    kokoro.setModel(model.getAbsolutePath());
                    kokoro.setVoices(voices.getAbsolutePath());
                    kokoro.setTokens(tokens.getAbsolutePath());
                    kokoro.setDataDir(dataDir == null ? "" : dataDir.getAbsolutePath());
                    File lexiconUs = findNamedFile(root, "lexicon-us-en.txt");
                    File lexiconZh = findNamedFile(root, "lexicon-zh.txt");
                    File kokoroDict = findDirectory(root, "dict");
                    if (lexiconUs == null || lexiconZh == null) {
                        throw new IOException("Kokoro 模型缺少中英文 lexicon 文件");
                    }
                    kokoro.setLexicon(lexiconUs.getAbsolutePath() + "," + lexiconZh.getAbsolutePath());
                    if (kokoroDict != null) kokoro.setDictDir(kokoroDict.getAbsolutePath());
                    modelConfig.setKokoro(kokoro);
                } else {
                    OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                    vits.setModel(model.getAbsolutePath());
                    vits.setTokens(tokens.getAbsolutePath());
                    vits.setDataDir(dataDir == null ? "" : dataDir.getAbsolutePath());
                    File lexicon = findNamedFile(root, "lexicon.txt");
                    File dict = findDirectory(root, "dict");
                    if (lexicon != null) vits.setLexicon(lexicon.getAbsolutePath());
                    if (dict != null) vits.setDictDir(dict.getAbsolutePath());
                    modelConfig.setVits(vits);
                }
                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(modelConfig);
                if (spec.kokoro) {
                    File phoneZh = findNamedFile(root, "phone-zh.fst");
                    File dateZh = findNamedFile(root, "date-zh.fst");
                    File numberZh = findNamedFile(root, "number-zh.fst");
                    if (phoneZh != null && dateZh != null && numberZh != null) {
                        config.setRuleFsts(phoneZh.getAbsolutePath() + ","
                                + dateZh.getAbsolutePath() + "," + numberZh.getAbsolutePath());
                    }
                }
                // All model files are extracted into the app's private files directory and
                // passed as absolute paths. Sherpa-ONNX requires a null AssetManager for
                // filesystem paths; passing getAssets() makes native file loading fail.
                Log.i(DOWNLOAD_TAG, "sherpa load model=" + model.getAbsolutePath()
                        + ", tokens=" + tokens.getAbsolutePath());
                OfflineTts localTts = new OfflineTts(null, config);
                GeneratedAudio audio = localTts.generate(text, 0, rate);
                File output = new File(getCacheDir(), "voicebench-sherpa-preview.wav");
                if (!audio.save(output.getAbsolutePath())) throw new IOException("音频生成失败");
                localTts.release();
                runOnUiThread(() -> {
                    generatedAudioFile = output;
                    generatedAudioEngineName = spec.displayName;
                    statusLabel.setText(spec.displayName + " 合成完成 · 请点击播放");
                });
            } catch (Exception error) {
                runOnUiThread(() -> statusLabel.setText("本地合成失败：" + error.getMessage()));
            }
        });
    }

    private void playGeneratedAudio(File audio, String engineName) {
        try {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(audio.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(player -> statusLabel.setText(engineName + " 播放完成"));
            mediaPlayer.prepare();
            mediaPlayer.start();
            statusLabel.setText(engineName + " 播放中 · 本地离线");
        } catch (Exception error) {
            statusLabel.setText("音频播放失败：" + error.getMessage());
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(round(CARD, 18, BORDER));
        return card;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round(PURPLE, 14, Color.TRANSPARENT));
        return button;
    }

    private Button pillButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(PURPLE_DARK);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(round(Color.rgb(239, 235, 255), 12, Color.TRANSPARENT));
        button.setOnClickListener(listener);
        return button;
    }

    private View space(int width) { View view = new View(this); view.setMinimumWidth(width); return view; }

    private android.graphics.drawable.GradientDrawable round(int color, int radius, int stroke) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int left, int top, int right, int bottom) {
        return lp(width, height, 0, left, top, right, bottom);
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (mediaPlayer != null) mediaPlayer.release();
        modelExecutor.shutdownNow();
        super.onDestroy();
    }

    private static class ModelSpec {
        final String id;
        final String displayName;
        final String archiveName;
        final String url;
        final boolean kokoro;

        ModelSpec(String id, String displayName, String archiveName, String url, boolean kokoro) {
            this.id = id;
            this.displayName = displayName;
            this.archiveName = archiveName;
            this.url = url;
            this.kokoro = kokoro;
        }
    }

    private static class EngineOption {
        final String id, name, kind, access, note, icon;
        final int color;
        EngineOption(String id, String name, String kind, String access, String note, String icon, int color) {
            this.id = id; this.name = name; this.kind = kind; this.access = access; this.note = note; this.icon = icon; this.color = color;
        }
    }
}
