# VoiceBench · 安卓语音引擎实验台

这是一个用于比较自然语言合成效果的 Android 测试项目。当前版本使用原生 Android View 和 `TextToSpeech`，不依赖第三方 UI 库。

## 已有能力

- 测试文本输入，内置中文和英文示例
- 系统 TTS：读取设备已安装的 TTS 引擎，支持切换默认引擎
- 真实离线基准播放：语速、音调调节，队列刷新播放
- 引擎目录：系统 TTS、Google TTS（Google Text-to-Speech）、Sherpa-ONNX（Piper / VITS）、Kokoro / MeloTTS，以及 OpenAI、Azure、Google Cloud、ElevenLabs、Amazon Polly、阿里云 TTS、腾讯云、百度、MiniMax、火山引擎和自定义 REST
- 云端配置弹窗：API Key / Access Key、Secret / Region、Endpoint、模型 / Voice ID、输出格式
- 配置使用本地 `SharedPreferences` 保存，不上传到任何服务

## 构建

在 Android Studio 中打开此目录即可。命令行构建：

```powershell
./gradlew.bat :app:assembleDebug
```

APK 输出在：`app/build/outputs/apk/debug/app-debug.apk`

## 使用建议

先用“系统 TTS”或“Google TTS”建立设备离线基准，再分别填写云端供应商的 API 配置进行接入。Sherpa-ONNX（Piper / VITS）、Kokoro / MeloTTS 目前作为本地模型候选目录展示，后续可把对应模型文件和推理运行时接入 `EngineOption` 的分支。

注意：本版本的云端条目已经有完整的选择和配置持久化入口；点击“合成并播放”时仍使用系统 TTS 做本地基准播放，尚未内置各云厂商的网络请求适配器。这样可以先在无 Key、无网络时完成 UI 和离线基准测试，再按正式产品选定的供应商补适配器。
