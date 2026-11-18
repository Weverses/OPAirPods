// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;
import java.util.concurrent.Executor;

/** The page holds these three samples only while the user is calibrating. */
final class HeadCalibrationDialog {
    private static final String[] INSTRUCTIONS = {
            "坐稳，看向正前方，保持半秒后记录。",
            "从正前方向左转头约 30°，保持半秒后记录。",
            "先转回正前方，再抬头约 30°，保持半秒后记录。",
            "回到开始时的正前方，保持半秒后完成。"
    };
    private final Activity activity;
    private final String address;
    private final BridgeClient bridge;
    private final Executor io;
    private final AlertDialog dialog;
    private final HeadOrientation.Pose[] samples = new HeadOrientation.Pose[3];
    private int step;
    private long epoch;

    HeadCalibrationDialog(Activity activity, String address, BridgeClient bridge, Executor io, Runnable onDismiss) {
        this.activity = activity;
        this.address = address;
        this.bridge = bridge;
        this.io = io;
        dialog = new AlertDialog.Builder(activity)
                .setTitle("校准头部方向")
                .setMessage(INSTRUCTIONS[0])
                .setPositiveButton("记录方向", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(d -> {
            java.util.Arrays.fill(samples, null);
            onDismiss.run();
        });
    }

    void show() {
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> capture());
    }

    void dismiss() { dialog.dismiss(); }

    private void capture() {
        boolean save = step == samples.length;
        Bundle request = BridgeClient.forDevice(address);
        request.putString("action", save ? "calibrate" : "sample");
        if (step > 0) request.putLong("epoch", epoch);
        if (save) {
            request.putDoubleArray("front", samples[0].components());
            request.putDoubleArray("left", samples[1].components());
            request.putDoubleArray("up", samples[2].components());
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        io.execute(() -> {
            try {
                Bundle result = bridge.request(BridgeClient.SPATIAL, request);
                activity.runOnUiThread(() -> received(result, save));
            } catch (Exception e) {
                activity.runOnUiThread(() -> showError(e));
            }
        });
    }

    private void received(Bundle result, boolean save) {
        if (!dialog.isShowing()) return;
        if (result.getBoolean("restart")) {
            step = 0;
            epoch = 0;
            java.util.Arrays.fill(samples, null);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("记录方向");
            showError(new IllegalStateException("追踪参考方向已改变，请从正前方重新开始"));
            return;
        }
        if (save) {
            dialog.dismiss();
            Toast.makeText(activity, "已保存这副耳机的头部方向", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            HeadOrientation.Pose pose = HeadOrientation.Pose.fromComponents(result.getDoubleArray("pose"));
            if (step == 1) HeadBasis.turn(samples[0], pose);
            if (step == 2) HeadBasis.calibrate(samples[0], samples[1], pose);
            samples[step++] = pose;
            epoch = result.getLong("epoch");
            dialog.setMessage(INSTRUCTIONS[step]);
            if (step == samples.length) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("完成");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        } catch (IllegalArgumentException e) { showError(e); }
    }

    private void showError(Exception error) {
        if (!dialog.isShowing()) return;
        dialog.setMessage(INSTRUCTIONS[step] + "\n\n" + error.getMessage());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
    }
}
