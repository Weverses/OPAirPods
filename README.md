# OPAirPods

OPAirPods 是一个 Android / LSPosed 模块，将 AirPods 的部分高级功能接入 ColorOS 原生耳机设置页面。项目使用宿主的设置界面和对话框，让耳机控制融入系统原有体验。

## 功能

- 在原生页面调整麦克风、按压与长按、音量滑动、单耳降噪、个性化音量和自适应强度等设置。
- 配置通透模式定制、响音降低和听力辅助相关参数。
- 查看多设备连接与音频来源，手动或在手机播放时切换音频。
- 显示多设备切换提示，并提供返回手机的操作。
- 查看连接与设置状态，恢复耳机控制连接。

## 上游与许可

本项目参考 [LibrePods](https://github.com/librepods-org/librepods) 的 AirPods 控制协议与多设备交接实现，并使用 [libxposed](https://github.com/libxposed/api) 接入系统。第三方组件及来源详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

项目采用 GPL-3.0-or-later 许可，详见 [LICENSE](LICENSE)。
