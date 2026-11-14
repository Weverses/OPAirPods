// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView help=new TextView(this);help.setTextSize(17);help.setPadding(48,128,48,48);
        help.setText("ColorPods\n\n请在 LSPosed 中启用本模块，勾选「我的设备」和「蓝牙」，然后重新启动这两个应用进程。\n\n所有设置均在 ColorOS 原生 AirPods 页面中，使用系统自己的 COUI 控件。\n\nApple 身份默认启用。修改身份后需关闭再开启蓝牙并重连耳机。\n\n适配 MyDevices 17.25.0\nlibxposed API 102\n基于 LibrePods 协议 · GPL-3.0-or-later");setContentView(help);}
}
