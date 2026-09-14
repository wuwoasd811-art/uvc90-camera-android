package com.codex.uvc90;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.jiangdg.usb.USBMonitor;
import com.jiangdg.uvc.IFrameCallback;
import com.jiangdg.uvc.UVCCamera;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String APP_VERSION = "4.2";
    private static final String ACTION_USB_PERMISSION = "com.codex.uvc90.USB_PERMISSION";
    private static final int CAMERA_PERMISSION_REQUEST = 90;
    private static final int CAMERA_VID = 0x1bcf;
    private static final int CAMERA_PID = 0x28c4;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int CAMERA_FPS = 90;
    private static final int BIT_RATE = 60_000_000;

    private final Handler ui = new Handler();
    private final AtomicLong frameCounter = new AtomicLong();
    private SurfaceView preview;
    private TextureView correctedPreview;
    private TextView status;
    private TextView fpsLabel;
    private TextView parameterLabel;
    private TextView exposureLabel;
    private TextView whiteBalanceLabel;
    private TextView brightnessLabel;
    private TextView redLabel;
    private TextView greenLabel;
    private TextView blueLabel;
    private TextView saturationLabel;
    private TextView previewRateLabel;
    private TextView controlStatusLabel;
    private LinearLayout controlPanel;
    private Button panelToggleButton;
    private Button recordButton;
    private Spinner recordFpsSpinner;
    private Spinner previewFpsSpinner;
    private SeekBar exposureSeekBar;
    private HardwareControl gainControl;
    private HardwareControl gammaControl;
    private HardwareControl brightnessControl;
    private HardwareControl saturationControl;
    private HardwareControl contrastControl;
    private HardwareControl hueControl;
    private HardwareControl sharpnessControl;
    private CheckBox autoWhiteBalanceCheck;
    private CheckBox backlightCheck;
    private CheckBox autoExposureCheck;
    private Spinner powerlineSpinner;
    private volatile boolean autoWhiteBalance = true;
    private volatile boolean backlightEnabled = true;
    private volatile boolean controlledAutoExposure = true;
    private volatile boolean gainSupported;
    private volatile int powerlineValue = 1;
    private volatile long lastAutoExposureSampleNs;
    private volatile long lastAutoGammaChangeNs;
    private volatile double smoothedCenterLuma = -1;
    private volatile double smoothedHighlightRatio = -1;
    private final AtomicBoolean autoExposureWritePending = new AtomicBoolean();
    private int autoExposureFailures;
    private SeekBar whiteBalanceSeekBar;
    private SeekBar brightnessSeekBar;
    private SeekBar redSeekBar;
    private SeekBar greenSeekBar;
    private SeekBar blueSeekBar;
    private SeekBar saturationSeekBar;
    private USBMonitor usbMonitor;
    private UsbManager usbManager;
    private boolean permissionReceiverRegistered;
    private UVCCamera camera;
    private USBMonitor.UsbControlBlock controlBlock;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread previewThread;
    private Handler previewHandler;
    private boolean surfaceReady;
    private boolean cameraOpening;
    private boolean permissionPending;
    private long previousFrames;
    private long previousRecordedFrames;
    private long previousTickNs;
    private volatile boolean recording;
    private volatile int selectedRecordFps = 90;
    private volatile int selectedPreviewFps = 15;
    private volatile int effectivePreviewFps = 15;
    private volatile int exposureUnits = 95; // UVC units: 100 us; new lens baseline 9.5 ms
    private volatile int whiteBalanceKelvin = -1;
    private volatile int brightnessLift = 0;
    private volatile int redBalance = 0;
    private volatile int greenBalance = 0;
    private volatile int blueBalance = 0;
    private volatile int saturationAdjust = 0;
    private volatile int[] lumaCurve = buildIdentityCurve();
    private volatile int[] uCurve = buildChromaCurve(0, 0);
    private volatile int[] vCurve = buildChromaCurve(0, 0);
    private volatile int actualExposureUnits = -1;
    private volatile int actualWhiteBalanceKelvin = -1;
    private volatile int actualPowerline = -1;
    private volatile double measuredInputFps;
    private int slowFpsTicks;
    private EncoderRecorder recorder;
    private PreviewRenderer previewRenderer;

    private final IFrameCallback cameraFrameCallback = new IFrameCallback() {
        @Override public void onFrame(ByteBuffer frame) {
            frameCounter.incrementAndGet();
            maybeAdjustControlledExposure(frame);
            PreviewRenderer renderer = previewRenderer;
            if (renderer != null) renderer.offer(frame, effectivePreviewFps);
            EncoderRecorder active = recorder;
            if (recording && active != null) {
                active.offer(frame);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashRecorder();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerPermissionReceiver();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        cameraThread = new HandlerThread("uvc-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        previewThread = new HandlerThread("uvc-light-preview");
        previewThread.start();
        previewHandler = new Handler(previewThread.getLooper());
        previewRenderer = new PreviewRenderer();
        String lastCrash = getSharedPreferences("diagnostics", MODE_PRIVATE).getString("last_crash", "");
        if (lastCrash.isEmpty()) {
            status.setText("UVC 90 相机 v" + APP_VERSION + " 已启动。\n请插入指定相机（1bcf:28c4），然后点击下方按钮授权。\n本版使用独立三星 USB 授权通道。");
        } else {
            status.setText("检测到上次异常：\n" + lastCrash + "\n\n请截图此页面发给开发者。");
            getSharedPreferences("diagnostics", MODE_PRIVATE).edit().remove("last_crash").apply();
        }
        ui.post(fpsTicker);
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter writer = new StringWriter();
                error.printStackTrace(new PrintWriter(writer));
                getSharedPreferences("diagnostics", MODE_PRIVATE).edit()
                        .putString("last_crash", writer.toString()).commit();
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        // The camera's direct Surface path interprets this device's chroma order
        // incorrectly. Draw the verified colour-correct callback above it.
        correctedPreview = new TextureView(this);
        correctedPreview.setOpaque(true);
        root.addView(correctedPreview, new FrameLayout.LayoutParams(-1, -1));

        controlPanel = new LinearLayout(this);
        controlPanel.setOrientation(LinearLayout.VERTICAL);
        controlPanel.setPadding(20, dp(58), 20, 14);
        controlPanel.setBackgroundColor(0xcc111827);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(390), -1, Gravity.END);
        root.addView(controlPanel, panelParams);

        fpsLabel = new TextView(this);
        fpsLabel.setText("UVC 90 专用相机");
        fpsLabel.setTextColor(Color.WHITE);
        fpsLabel.setTextSize(20);
        fpsLabel.setPadding(0, 0, 0, 10);
        controlPanel.addView(fpsLabel);

        ScrollView scroll = new ScrollView(this);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        parameterLabel = makeLabel("", 14, 0xffbfdbfe);
        parameterLabel.setPadding(0, 0, 0, 8);
        controls.addView(parameterLabel);

        controls.addView(makeLabel("录像帧率（默认 90 FPS）", 13, Color.WHITE));
        recordFpsSpinner = new Spinner(this);
        recordFpsSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"90 FPS（采集要求）", "60 FPS", "30 FPS"}));
        recordFpsSpinner.setSelection(selectedRecordFps == 60 ? 1 : (selectedRecordFps == 30 ? 2 : 0));
        recordFpsSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedRecordFps = position == 0 ? 90 : (position == 1 ? 60 : 30);
                updateParameterLabel();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        controls.addView(recordFpsSpinner);

        controls.addView(makeLabel("实时预览流畅度", 13, Color.WHITE));
        previewFpsSpinner = new Spinner(this);
        previewFpsSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"15 FPS（默认，1080p）", "20 FPS（更流畅）", "10 FPS（更稳定）", "5 FPS（最低负载）"}));
        int previewPosition = selectedPreviewFps == 20 ? 1 : (selectedPreviewFps == 10 ? 2 :
                (selectedPreviewFps == 5 ? 3 : 0));
        previewFpsSpinner.setSelection(previewPosition);
        previewFpsSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int[] rates = {15, 20, 10, 5};
                selectedPreviewFps = rates[position];
                effectivePreviewFps = selectedPreviewFps;
                updateParameterLabel();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        controls.addView(previewFpsSpinner);

        exposureLabel = makeLabel(String.format(Locale.US, "曝光时间：%.1f ms", exposureUnits / 10.0), 13, Color.WHITE);
        controls.addView(exposureLabel);
        exposureSeekBar = new SeekBar(this);
        exposureSeekBar.setMax(95); // 1.0 .. 10.5 ms; 90 FPS frame period is 11.1 ms
        exposureSeekBar.setProgress(Math.max(0, Math.min(95, exposureUnits - 10)));
        exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                exposureUnits = progress + 10;
                exposureLabel.setText(String.format(Locale.US, "曝光时间：%.1f ms", exposureUnits / 10.0));
                updateParameterLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { applySelectedControlsAsync(true, false); }
        });
        controls.addView(exposureSeekBar);

        autoExposureCheck = new CheckBox(this);
        autoExposureCheck.setText("暗处自动增强（曝光＋Gamma＋亮度，保持90 FPS）");
        autoExposureCheck.setTextColor(0xff86efac);
        autoExposureCheck.setChecked(true);
        exposureSeekBar.setEnabled(false);
        autoExposureCheck.setOnCheckedChangeListener((button, checked) -> {
            controlledAutoExposure = checked;
            exposureSeekBar.setEnabled(!checked);
            if (brightnessControl != null && brightnessControl.bar != null) {
                brightnessControl.bar.setEnabled(!checked);
            }
            if (gammaControl != null && gammaControl.bar != null) {
                gammaControl.bar.setEnabled(!checked);
            }
            smoothedCenterLuma = -1;
            smoothedHighlightRatio = -1;
            autoExposureFailures = 0;
            updateParameterLabel();
            if (button.isPressed()) showToast(checked
                    ? "暗处增强已开启；正常光线会恢复Gamma 130"
                    : "已切换为手动曝光");
        });
        controls.addView(autoExposureCheck);

        gainControl = addHardwareSlider(controls, "ISO/增益（连接后检测）", 0x04, 0, 1, 0, false);
        gainControl.supported = false;
        gainControl.bar.setVisibility(View.GONE);
        gainControl.bar.setEnabled(false);
        gainControl.label.setText("ISO/增益：正在等待相机能力检测");
        gainControl.label.setTextColor(0xffffc857);
        gammaControl = addHardwareSlider(controls, "Gamma", 0x09, 100, 300, 130, false);
        gammaControl.bar.setEnabled(false);
        brightnessControl = addHardwareSlider(controls, "亮度", 0x02, -64, 64, 0, true);
        brightnessControl.bar.setEnabled(false);
        saturationControl = addHardwareSlider(controls, "饱和度", 0x07, 0, 100, 64, false);
        contrastControl = addHardwareSlider(controls, "对比度", 0x03, 0, 95, 0, false);
        hueControl = addHardwareSlider(controls, "色调 Hue", 0x06, -2000, 2000, 0, true);
        sharpnessControl = addHardwareSlider(controls, "锐度", 0x08, 1, 7, 2, false);

        autoWhiteBalanceCheck = new CheckBox(this);
        autoWhiteBalanceCheck.setText("自动白平衡");
        autoWhiteBalanceCheck.setTextColor(Color.WHITE);
        autoWhiteBalanceCheck.setChecked(true);
        autoWhiteBalanceCheck.setOnCheckedChangeListener((button, checked) -> {
            autoWhiteBalance = checked;
            if (button.isPressed()) applyProcessingUnitControlAsync(0x0b, 1,
                    checked ? 1 : 0, false, "自动白平衡");
        });
        controls.addView(autoWhiteBalanceCheck);

        backlightCheck = new CheckBox(this);
        backlightCheck.setText("逆光补偿");
        backlightCheck.setTextColor(Color.WHITE);
        backlightCheck.setChecked(true);
        backlightCheck.setOnCheckedChangeListener((button, checked) -> {
            backlightEnabled = checked;
            if (button.isPressed()) applyProcessingUnitControlAsync(0x01, 2,
                    checked ? 1 : 0, false, "逆光补偿");
        });
        controls.addView(backlightCheck);

        controls.addView(makeLabel("防频闪", 13, Color.WHITE));
        powerlineSpinner = new Spinner(this);
        powerlineSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"50 Hz（默认）", "60 Hz"}));
        powerlineSpinner.setSelection(0);
        powerlineSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                int selected = position == 0 ? 1 : 2;
                boolean changed = selected != powerlineValue;
                powerlineValue = selected;
                if (changed && camera != null) applyProcessingUnitControlAsync(0x05, 1,
                        powerlineValue, false, "防频闪");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        controls.addView(powerlineSpinner);

        controls.addView(makeLabel("正常光线Gamma 130；弱光时临时提高Gamma和亮度，恢复后自动还原", 12, 0xff86efac));
        controls.addView(makeLabel("预览：完整 1920×1080，不裁剪、不切换分辨率", 12, 0xffbfdbfe));

        previewRateLabel = makeLabel("1080p预览实际目标：15 FPS（自动保护开启）", 12, 0xff86efac);
        controls.addView(previewRateLabel);

        controlStatusLabel = makeLabel("相机实读：连接后显示", 12, 0xffffc857);
        controlStatusLabel.setPadding(0, 4, 0, 0);
        controls.addView(controlStatusLabel);

        status = new TextView(this);
        status.setTextColor(0xffd1fae5);
        status.setTextSize(14);
        status.setTextIsSelectable(true);
        status.setPadding(0, 10, 0, 10);
        controls.addView(status);
        scroll.addView(controls);
        controlPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Button reconnect = new Button(this);
        reconnect.setText("请求权限并连接 1080p90");
        reconnect.setOnClickListener(v -> reconnectCamera());
        controlPanel.addView(reconnect);

        recordButton = new Button(this);
        recordButton.setText("开始录像（60 Mbps）");
        recordButton.setEnabled(false);
        recordButton.setOnClickListener(v -> toggleRecording());
        controlPanel.addView(recordButton);

        panelToggleButton = new Button(this);
        panelToggleButton.setText("收起参数");
        panelToggleButton.setTextSize(12);
        panelToggleButton.setPadding(6, 0, 6, 0);
        panelToggleButton.setOnClickListener(v -> {
            boolean showing = controlPanel.getVisibility() == View.VISIBLE;
            controlPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
            panelToggleButton.setText(showing ? "展开参数" : "收起参数");
        });
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(
                dp(112), dp(48), Gravity.TOP | Gravity.END);
        toggleParams.setMargins(0, dp(6), dp(8), 0);
        root.addView(panelToggleButton, toggleParams);
        setContentView(root);
        updateParameterLabel();
    }

    private TextView makeLabel(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private void updateParameterLabel() {
        if (parameterLabel == null) return;
        parameterLabel.setText(String.format(Locale.US,
                "固定采集：MJPEG 1920×1080 @ 90 FPS\n录像 %d FPS · 1080p预览 %d FPS\n曝光 %.1f ms（%s）· %s · 白平衡自动",
                selectedRecordFps, selectedPreviewFps, exposureUnits / 10.0,
                controlledAutoExposure ? "暗处自动增亮" : "手动", gainSummary()));
    }

    private String gainSummary() {
        if (camera == null && !gainSupported) return "ISO/增益待检测";
        return gainSupported ? "硬件ISO/增益 " + gainControl.value : "ISO/增益未开放";
    }

    private interface SoftwareValueChanged { void onChanged(int value); }

    private final class HardwareControl {
        final String name;
        final int selector;
        final boolean signed;
        final int defaultValue;
        volatile int min;
        volatile int max;
        volatile int value;
        volatile boolean supported = true;
        TextView label;
        SeekBar bar;

        HardwareControl(String name, int selector, int min, int max,
                        int defaultValue, boolean signed) {
            this.name = name;
            this.selector = selector;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
            this.signed = signed;
        }

        void refreshUi() {
            if (label != null) label.setText(name + "：" + value + "（" + min + "～" + max + "）");
            if (bar != null) {
                bar.setMax(Math.max(1, max - min));
                bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
            }
        }
    }

    private HardwareControl addHardwareSlider(LinearLayout controls, String name, int selector,
                                               int min, int max, int initial, boolean signed) {
        HardwareControl control = new HardwareControl(name, selector, min, max, initial, signed);
        control.label = makeLabel("", 13, Color.WHITE);
        controls.addView(control.label);
        control.bar = new SeekBar(this);
        control.bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) control.value = control.min + progress;
                control.label.setText(control.name + "：" + control.value +
                        "（" + control.min + "～" + control.max + "）");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                applyPictureControlAsync(control);
            }
        });
        controls.addView(control.bar);
        control.refreshUi();
        return control;
    }

    private SeekBar addSoftwareSlider(LinearLayout controls, int initial,
                                      SoftwareValueChanged listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(48); // -24 .. +24
        bar.setProgress(initial + 24);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onChanged(progress - 24);
                rebuildSoftwareColorLuts();
                updateParameterLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                showToast("校准已同步应用到预览和录像");
            }
        });
        controls.addView(bar);
        return bar;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private void rebuildSoftwareColorLuts() {
        lumaCurve = buildIdentityCurve();
        uCurve = buildChromaCurve(blueBalance - greenBalance, saturationAdjust);
        vCurve = buildChromaCurve(redBalance - greenBalance, saturationAdjust);
    }

    private static int[] buildLumaCurve(int amount) {
        int[] curve = new int[256];
        // Positive values use a gamma below 1.0: shadows and midtones rise much
        // more than highlights, preserving the bright windows and displays.
        double gamma = Math.max(0.45, Math.min(1.45, 1.0 - amount * 0.0225));
        for (int i = 0; i < 256; i++) {
            curve[i] = clampByte((int) Math.round(Math.pow(i / 255.0, gamma) * 255.0));
        }
        return curve;
    }

    private static int[] buildIdentityCurve() {
        int[] curve = new int[256];
        for (int i = 0; i < 256; i++) curve[i] = i;
        return curve;
    }

    private static int[] buildChromaCurve(int offset, int saturation) {
        int[] curve = new int[256];
        double scale = Math.max(0.5, 1.0 + saturation / 100.0);
        for (int i = 0; i < 256; i++) {
            curve[i] = clampByte((int) Math.round(128 + (i - 128) * scale + offset));
        }
        return curve;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private final USBMonitor.OnDeviceConnectListener connectionListener = new USBMonitor.OnDeviceConnectListener() {
        @Override public void onAttach(UsbDevice device) {
            if (isTarget(device)) {
                resetDefaultsForNewCamera();
                show("已检测到目标相机：1bcf:28c4\n尚未请求 USB 权限。\n请点击‘请求权限并连接 1080p90’。");
            }
        }
        @Override public void onDetach(UsbDevice device) {
            if (isTarget(device)) {
                permissionPending = false;
                stopCamera();
                show("相机已拔出");
            }
        }
        @Override public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock block, boolean createNew) {
            if (!isTarget(device)) return;
            permissionPending = false;
            show("已经获得 USB 权限，正在打开 1080p90……");
            controlBlock = block;
            openWhenReady();
        }
        @Override public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock block) {
            if (isTarget(device)) stopCamera();
        }
        @Override public void onCancel(UsbDevice device) {
            permissionPending = false;
            boolean granted = device != null && usbManager.hasPermission(device);
            show("v" + APP_VERSION + "：USB 设备打开失败。\n系统当前权限=" + granted +
                    "\n这已不是授权弹窗问题，可能是设备被占用、供电不足或底层打开失败。");
        }
    };

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            permissionPending = false;
            UsbDevice returned = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean resultExtra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice target = isTarget(returned) ? returned : findTargetDevice();
            boolean managerGranted = target != null && usbManager.hasPermission(target);
            if (!managerGranted) {
                show("v" + APP_VERSION + "：三星系统返回授权失败。\n弹窗结果=" + resultExtra +
                        "，系统实际权限=false。\n请拔下相机、重启手机后再试；也请确认弹窗按的是‘允许’而不是‘取消’。");
                return;
            }
            show("v" + APP_VERSION + "：系统实际权限=true，正在打开摄像机……");
            if (usbMonitor == null || !usbMonitor.connectGrantedDevice(target)) {
                show("v" + APP_VERSION + "：权限已获得，但无法交给相机组件。请重新插拔后再试。");
            }
        }
    };

    private void registerPermissionReceiver() {
        if (permissionReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        permissionReceiverRegistered = true;
    }

    private UsbDevice findTargetDevice() {
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isTarget(device)) return device;
        }
        return null;
    }

    private boolean isTarget(UsbDevice device) {
        return device != null && device.getVendorId() == CAMERA_VID && device.getProductId() == CAMERA_PID;
    }

    private void openWhenReady() {
        if (!surfaceReady || controlBlock == null || cameraOpening || camera != null) return;
        cameraOpening = true;
        cameraHandler.post(() -> {
            UVCCamera newCamera = null;
            try {
                // Send controls before libuvc clones/opens another file descriptor.
                // This camera rejects endpoint-0 controls while a second owner exists.
                ControlResult controls = applyManualControlsFallback(controlBlock, "libuvc打开前");
                newCamera = new UVCCamera();
                newCamera.open(controlBlock);
                String modes = newCamera.getSupportedSize();
                newCamera.setPreviewSize(WIDTH, HEIGHT, CAMERA_FPS, CAMERA_FPS,
                        UVCCamera.FRAME_FORMAT_MJPEG, 1.0f);
                // This older UVC implementation needs a valid Surface while the
                // stream starts on some Samsung builds. Detach it shortly after
                // frames begin, leaving only the corrected low-rate preview.
                newCamera.setPreviewDisplay(preview.getHolder());
                newCamera.setFrameCallback(cameraFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
                newCamera.startPreview();
                camera = newCamera;
                final UVCCamera openedCamera = newCamera;
                cameraHandler.postDelayed(() -> {
                    if (camera == openedCamera) {
                        try { openedCamera.setPreviewDisplay((Surface) null); }
                        catch (Throwable ignored) { }
                    }
                }, 700);
                previousFrames = frameCounter.get();
                previousTickNs = System.nanoTime();
                show(String.format(Locale.US,
                        "连接成功\n采集模式：MJPEG 1920×1080 @ 90 FPS\n" +
                        "全程保持1920×1080，不切换分辨率、不裁剪\n" +
                        "当前设定：录像 %d FPS；1080p预览 %d FPS；曝光 %.1f ms；%s；白平衡自动；防频闪50 Hz\n" +
                        "新相机基准：Gamma 130；亮度 0；饱和度 64；对比度 0；色调 0；锐度 2；逆光补偿开\n" +
                        "新镜头暗处增强：开启，曝光9.5～10.5 ms，Gamma最高170，亮度最高+12\n\n相机报告的模式：\n%s",
                        selectedRecordFps, selectedPreviewFps, exposureUnits / 10.0,
                        gainSummary(), modes));
                showControlResult(controls);
                ui.post(() -> recordButton.setEnabled(true));
            } catch (Throwable error) {
                if (newCamera != null) {
                    try { newCamera.destroy(); } catch (Throwable ignored) { }
                }
                show("无法启动 1080p90：" + error.getMessage() +
                        "\n这通常表示手机 USB 通路或相机固件没有接受该模式。");
            } finally {
                cameraOpening = false;
            }
        });
    }

    private void reconnectCamera() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            show("v" + APP_VERSION + "：需要先取得系统‘相机’权限。\n这是 Android 对外接 UVC 视频设备的强制要求，不会使用手机内置摄像头。\n请在弹窗中选择‘使用应用时允许’。");
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        if (!ensureUsbMonitor()) return;
        stopCamera();
        try {
            if (!usbMonitor.isRegistered()) usbMonitor.register();
        } catch (Throwable error) {
            show("USB 初始化失败：\n" + error);
            return;
        }
        for (UsbDevice device : usbMonitor.getDeviceList()) {
            if (isTarget(device)) {
                if (permissionPending) {
                    show("正在等待三星系统显示 USB 授权窗口，请在弹窗中选择‘允许’。\n如果 8 秒仍无弹窗，可再次点击按钮。");
                    return;
                }
                permissionPending = true;
                show("已检测到目标相机：1bcf:28c4\n正在请求三星系统授予 USB 权限……\n请在系统弹窗中选择‘允许’。");
                Intent resultIntent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                PendingIntent result = PendingIntent.getBroadcast(this, 7, resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                usbManager.requestPermission(device, result);
                ui.postDelayed(() -> {
                    if (permissionPending) {
                        permissionPending = false;
                        show("系统授权窗口未出现或没有完成。\n请退出其他 USB 相机 App，重新插拔摄像机，再点击按钮。");
                    }
                }, 8000);
                return;
            }
        }
        show("没有检测到目标相机。请插入摄像机，等待显示‘已检测到目标相机’，再点击按钮。");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            show("v" + APP_VERSION + "：系统‘相机’权限已允许。\n现在继续请求外接 USB 相机权限……");
            reconnectCamera();
        } else {
            show("v" + APP_VERSION + "：系统‘相机’权限被拒绝。\nAndroid 不允许未取得此权限的 App 访问 UVC 摄像机。请再次点击按钮并选择允许。");
        }
    }

    private boolean ensureUsbMonitor() {
        if (usbMonitor != null) return true;
        try {
            usbMonitor = new USBMonitor(this, connectionListener);
            return true;
        } catch (Throwable error) {
            show("USB 组件加载失败：\n" + error);
            return false;
        }
    }

    private void stopCamera() {
        cameraHandler.post(() -> {
            stopRecordingInternal();
            UVCCamera old = camera;
            camera = null;
            if (old != null) {
                try { old.stopPreview(); } catch (Throwable ignored) { }
                try { old.destroy(); } catch (Throwable ignored) { }
            }
            controlBlock = null;
            ui.post(() -> recordButton.setEnabled(false));
        });
    }

    private void toggleRecording() {
        cameraHandler.post(() -> {
            if (recording) stopRecordingInternal(); else startRecordingInternal();
        });
    }

    private void startRecordingInternal() {
        if (camera == null || recording) return;
        try {
            recorder = new EncoderRecorder();
            recorder.start();
            // Stop the library's unthrottled 90 fps Surface preview. PreviewRenderer
            // keeps the full 1920x1080 image but displays only the requested cadence.
            camera.setPreviewDisplay((Surface) null);
            // This libuvc build's PIXEL_FORMAT_NV21 label maps to the native
            // YUYV -> UV-interleaved converter. Samsung's AVC encoder expects
            // that UV order for COLOR_FormatYUV420SemiPlanar; the other callback
            // format produces VU here and swaps red/blue in the saved video.
            camera.setFrameCallback(cameraFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
            effectivePreviewFps = selectedPreviewFps;
            slowFpsTicks = 0;
            previousRecordedFrames = 0;
            previewRenderer.reset();
            recording = true;
            showToast("开始录像：1080p / " + recorder.encoderFps + " FPS / " +
                    (recorder.encoderBitRate / 1_000_000) + " Mbps / 预览 " + selectedPreviewFps + " FPS");
            show("正在录像：MJPEG 1920×1080 @ 90 FPS\n" +
                    "H.264 录像 " + recorder.encoderFps + " FPS / 60 Mbps\n" +
                    "完整1080p实时预览 " + selectedPreviewFps + " FPS；不裁剪、不切换分辨率；性能不足时仅降低显示帧率。");
            ui.post(() -> {
                recordButton.setText("停止并保存录像");
                recordFpsSpinner.setEnabled(false);
            });
        } catch (Throwable error) {
            if (recorder != null) recorder.abort();
            recorder = null;
            try { if (camera != null) camera.setPreviewDisplay((Surface) null); }
            catch (Throwable ignored) { }
            showToast("录像启动失败：" + error.getMessage());
        }
    }

    private void stopRecordingInternal() {
        if (!recording) return;
        recording = false;
        try { if (camera != null) camera.setFrameCallback(cameraFrameCallback, UVCCamera.PIXEL_FORMAT_NV21); }
        catch (Throwable ignored) { }
        try { if (camera != null) camera.setPreviewDisplay((Surface) null); }
        catch (Throwable ignored) { }
        EncoderRecorder old = recorder;
        recorder = null;
        if (old != null) old.finish();
        if (previewRenderer != null) previewRenderer.reset();
        ui.post(() -> {
            recordButton.setText("开始录像（60 Mbps）");
            recordFpsSpinner.setEnabled(true);
        });
    }

    private ControlResult applyManualControls(UVCCamera targetCamera, USBMonitor.UsbControlBlock block) {
        // Use the native camera handle first. It owns the claimed UVC interface,
        // so this works on Samsung devices where a second Android controlTransfer
        // is ignored while streaming. Reflection is only needed because this
        // library accidentally declares its absolute-value controls private.
        try {
            Field pointerField = UVCCamera.class.getDeclaredField("mNativePtr");
            pointerField.setAccessible(true);
            long pointer = pointerField.getLong(targetCamera);

            Method setAe = privateNative("nativeSetExposureMode", long.class, int.class);
            Method setExposure = privateNative("nativeSetExposure", long.class, int.class);
            Method getExposure = privateNative("nativeGetExposure", long.class);
            Method setAutoWb = privateNative("nativeSetAutoWhiteBlance", long.class, boolean.class);

            int aeResult = (Integer) setAe.invoke(null, pointer, 1);
            int exposureResult = (Integer) setExposure.invoke(null, pointer, exposureUnits);
            int wbAutoResult = (Integer) setAutoWb.invoke(null, pointer, true);
            targetCamera.setPowerlineFrequency(3);
            try { Thread.sleep(60); } catch (InterruptedException ignored) { }

            int readExposure = (Integer) getExposure.invoke(null, pointer);
            int readPowerline = targetCamera.getPowerlineFrequency();
            boolean exposureOk = aeResult == 0 && exposureResult == 0 && readExposure > 0;
            boolean wbOk = wbAutoResult == 0;
            if (exposureOk) {
                return new ControlResult(true, wbOk, readExposure, -1, readPowerline,
                        "原生 UVC 控制");
            }
            String nativeStatus = "native AE=" + aeResult + ", EXP=" + exposureResult +
                    ", AWB=" + wbAutoResult;
            return applyManualControlsFallback(block, nativeStatus);
        } catch (Throwable nativeError) {
            return applyManualControlsFallback(block, nativeError.getClass().getSimpleName());
        }
    }

    private Method privateNative(String name, Class<?>... parameterTypes) throws Exception {
        Method method = UVCCamera.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private ControlResult applyManualControlsFallback(USBMonitor.UsbControlBlock block, String reason) {
        UsbDeviceConnection connection = null;
        android.hardware.usb.UsbInterface claimedInterface = null;
        try {
            connection = block.getConnection();
            byte[] descriptors = block.getRawDescriptors();
            int controlInterface = findVideoControlInterface(block.getDevice());
            claimedInterface = block.getInterface(controlInterface);
            if (claimedInterface == null || !connection.claimInterface(claimedInterface, true)) {
                throw new IllegalStateException("VC" + controlInterface + "接口占用失败");
            }
            String extensionInfo = describeExtensionUnits(descriptors);
            String extensionProbe = probeExtensionControls(connection, descriptors, controlInterface);
            int cameraTerminal = findEntity(descriptors, 0x02, true);
            int processingUnit = findEntity(descriptors, 0x05, false);
            detectGainControl(connection, controlInterface, processingUnit);
            int exposureSet = -1;
            int aeSet = -1;
            int autoWbSet = -1;
            int powerlineSet = -1;
            int backlightSet = -1;
            int brightnessSet = -1;
            int contrastSet = -1;
            int hueSet = -1;
            int saturationSet = -1;
            int sharpnessSet = -1;
            int gammaSet = -1;
            if (cameraTerminal > 0) {
                aeSet = setUvcRetry(connection, controlInterface, cameraTerminal, 0x02, new byte[]{1});
                try { Thread.sleep(80); } catch (InterruptedException ignored) { }
                exposureSet = setUvcRetry(connection, controlInterface, cameraTerminal, 0x04,
                        littleEndian(exposureUnits, 4));
            }
            if (processingUnit > 0) {
                // Tested native UVC picture profile for this exact camera. Apply it
                // before libuvc starts streaming: this firmware may reset or blank
                // its stream when several Processing Unit controls change live.
                backlightSet = setUvcRetry(connection, controlInterface, processingUnit, 0x01,
                        littleEndian(backlightEnabled ? 1 : 0, 2));
                brightnessSet = setUvcRetry(connection, controlInterface, processingUnit, 0x02,
                        littleEndian(brightnessControl.value, 2));
                contrastSet = setUvcRetry(connection, controlInterface, processingUnit, 0x03,
                        littleEndian(contrastControl.value, 2));
                hueSet = setUvcRetry(connection, controlInterface, processingUnit, 0x06,
                        littleEndian(hueControl.value, 2));
                saturationSet = setUvcRetry(connection, controlInterface, processingUnit, 0x07,
                        littleEndian(saturationControl.value, 2));
                sharpnessSet = setUvcRetry(connection, controlInterface, processingUnit, 0x08,
                        littleEndian(sharpnessControl.value, 2));
                gammaSet = setUvcRetry(connection, controlInterface, processingUnit, 0x09,
                        littleEndian(gammaControl.value, 2));
                autoWbSet = setUvcRetry(connection, controlInterface, processingUnit, 0x0b,
                        new byte[]{(byte) (autoWhiteBalance ? 1 : 0)});
                powerlineSet = setUvcRetry(connection, controlInterface, processingUnit, 0x05,
                        new byte[]{(byte) powerlineValue});
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            int readExposure = readLittleEndian(getUvc(connection, controlInterface, cameraTerminal, 0x04, 4));
            int readPowerline = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x05, 1));
            int readBacklight = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x01, 2));
            int readBrightness = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x02, 2));
            int readContrast = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x03, 2));
            int readHue = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x06, 2));
            int readSaturation = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x07, 2));
            int readSharpness = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x08, 2));
            int readGamma = readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x09, 2));
            int readGain = gainSupported
                    ? readLittleEndian(getUvc(connection, controlInterface, processingUnit, 0x04, 2)) : -1;
            updateHardwareControlRanges(connection, controlInterface, processingUnit);
            connection.releaseInterface(claimedInterface);
            claimedInterface = null;
            return new ControlResult(exposureSet == 4 && readExposure > 0, true,
                    readExposure, -1, readPowerline,
                    "USB直控 VC=" + controlInterface + " CT=" + cameraTerminal + " PU=" + processingUnit +
                            " [AE " + aeSet + ", EXP " + exposureSet + ", AWB " + autoWbSet +
                            ", " + (gainSupported ? "ISO/增益实读 " + readGain : "ISO/增益未开放") +
                            ", 50Hz " + powerlineSet +
                            "]\n画质写入：Gamma " + gammaSet + " 亮度 " + brightnessSet +
                            " 对比度 " + contrastSet + " 色调 " + hueSet +
                            " 饱和度 " + saturationSet + " 锐度 " + sharpnessSet +
                            " 逆光 " + backlightSet +
                            "\n画质实读：Gamma " + readGamma + " 亮度 " + readBrightness +
                            " 对比度 " + readContrast + " 色调 " + readHue +
                            " 饱和度 " + readSaturation + " 锐度 " + readSharpness +
                            " 逆光 " + readBacklight + " 防频闪 " + readPowerline +
                            "；数字变焦 0（固定完整画面）\n" + reason +
                            extensionInfo + extensionProbe);
        } catch (Throwable error) {
            if (connection != null && claimedInterface != null) {
                try { connection.releaseInterface(claimedInterface); } catch (Throwable ignored) { }
            }
            return new ControlResult(false, false, -1, -1, -1,
                    "控制失败：" + error.getClass().getSimpleName() + "：" + error.getMessage());
        }
    }

    private int findVideoControlInterface(UsbDevice device) {
        if (device == null) return 0;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 14 && intf.getInterfaceSubclass() == 1) return intf.getId();
        }
        return 0;
    }

    private String describeExtensionUnits(byte[] data) {
        if (data == null) return "";
        StringBuilder info = new StringBuilder();
        for (int p = 0; p + 21 < data.length;) {
            int len = data[p] & 0xff;
            if (len < 3 || p + len > data.length) { p++; continue; }
            if ((data[p + 1] & 0xff) == 0x24 && (data[p + 2] & 0xff) == 0x06 && len >= 24) {
                int unit = data[p + 3] & 0xff;
                int controls = data[p + 20] & 0xff;
                int pins = data[p + 21] & 0xff;
                int sizeOffset = p + 22 + pins;
                info.append("\nXU id=").append(unit).append(" controls=").append(controls).append(" guid=");
                for (int i = 4; i < 20; i++) info.append(String.format(Locale.US, "%02x", data[p + i] & 0xff));
                if (sizeOffset < p + len) {
                    int controlBytes = data[sizeOffset] & 0xff;
                    info.append(" bm=");
                    for (int i = 0; i < controlBytes && sizeOffset + 1 + i < p + len; i++) {
                        info.append(String.format(Locale.US, "%02x", data[sizeOffset + 1 + i] & 0xff));
                    }
                }
            }
            p += len;
        }
        return info.toString();
    }

    private String probeExtensionControls(UsbDeviceConnection connection, byte[] data, int controlInterface) {
        if (data == null || connection == null) return "\nXU探测：无描述符";
        StringBuilder report = new StringBuilder("\nXU只读探测：");
        for (int p = 0; p + 23 < data.length;) {
            int len = data[p] & 0xff;
            if (len < 3 || p + len > data.length) { p++; continue; }
            if ((data[p + 1] & 0xff) == 0x24 && (data[p + 2] & 0xff) == 0x06 && len >= 24) {
                int unit = data[p + 3] & 0xff;
                int pins = data[p + 21] & 0xff;
                int sizeOffset = p + 22 + pins;
                if (sizeOffset < p + len) {
                    int controlBytes = data[sizeOffset] & 0xff;
                    report.append("\nXU").append(unit).append(':');
                    for (int bit = 0; bit < controlBytes * 8; bit++) {
                        int byteOffset = sizeOffset + 1 + bit / 8;
                        if (byteOffset >= p + len || ((data[byteOffset] >> (bit % 8)) & 1) == 0) continue;
                        int selector = bit + 1;
                        byte[] lengthBytes = getUvcRequest(connection, controlInterface, unit,
                                selector, 0x85, 2, 120);
                        int controlLength = readLittleEndian(lengthBytes);
                        report.append(" S").append(selector);
                        if (controlLength <= 0 || controlLength > 64) {
                            report.append("!");
                            continue;
                        }
                        byte[] info = getUvcRequest(connection, controlInterface, unit,
                                selector, 0x86, 1, 120);
                        byte[] current = getUvcRequest(connection, controlInterface, unit,
                                selector, 0x81, controlLength, 120);
                        byte[] min = getUvcRequest(connection, controlInterface, unit,
                                selector, 0x82, controlLength, 120);
                        byte[] max = getUvcRequest(connection, controlInterface, unit,
                                selector, 0x83, controlLength, 120);
                        report.append("[L").append(controlLength)
                                .append(" I").append(hex(info))
                                .append(" C").append(hex(current))
                                .append(" N").append(hex(min))
                                .append(" X").append(hex(max)).append(']');
                    }
                }
            }
            p += len;
        }
        return report.toString();
    }

    private byte[] getUvcRequest(UsbDeviceConnection connection, int controlInterface, int entity,
                                 int selector, int request, int length, int timeoutMs) {
        byte[] value = new byte[length];
        int index = (entity << 8) | (controlInterface & 0xff);
        int result = connection.controlTransfer(0xa1, request, selector << 8, index,
                value, value.length, timeoutMs);
        return result == length ? value : null;
    }

    private String hex(byte[] bytes) {
        if (bytes == null) return "-";
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private void resetDefaultsForNewCamera() {
        selectedRecordFps = 90;
        selectedPreviewFps = 15;
        effectivePreviewFps = 15;
        exposureUnits = 95;
        controlledAutoExposure = true;
        gainSupported = false;
        lastAutoExposureSampleNs = 0;
        lastAutoGammaChangeNs = 0;
        smoothedCenterLuma = -1;
        smoothedHighlightRatio = -1;
        autoExposureFailures = 0;
        autoExposureWritePending.set(false);
        resetHardwareControl(gammaControl);
        resetHardwareControl(gainControl);
        resetHardwareControl(brightnessControl);
        resetHardwareControl(saturationControl);
        resetHardwareControl(contrastControl);
        resetHardwareControl(hueControl);
        resetHardwareControl(sharpnessControl);
        autoWhiteBalance = true;
        backlightEnabled = true;
        powerlineValue = 1;
        whiteBalanceKelvin = -1;
        brightnessLift = 0;
        redBalance = 0;
        greenBalance = 0;
        blueBalance = 0;
        saturationAdjust = 0;
        rebuildSoftwareColorLuts();
        actualExposureUnits = -1;
        actualWhiteBalanceKelvin = -1;
        actualPowerline = -1;
        slowFpsTicks = 0;
        ui.post(() -> {
            if (recordFpsSpinner != null) recordFpsSpinner.setSelection(0);
            if (previewFpsSpinner != null) previewFpsSpinner.setSelection(0);
            if (exposureSeekBar != null) exposureSeekBar.setProgress(85);
            if (exposureSeekBar != null) exposureSeekBar.setEnabled(false);
            if (exposureLabel != null) exposureLabel.setText("曝光时间：9.5 ms");
            if (autoExposureCheck != null) autoExposureCheck.setChecked(true);
            if (gainControl != null) {
                gainControl.supported = false;
                gainControl.label.setText("ISO/增益：正在等待相机能力检测");
                gainControl.label.setTextColor(0xffffc857);
                gainControl.bar.setVisibility(View.GONE);
                gainControl.bar.setEnabled(false);
            }
            if (gammaControl != null) gammaControl.refreshUi();
            if (gammaControl != null && gammaControl.bar != null) gammaControl.bar.setEnabled(false);
            if (brightnessControl != null) brightnessControl.refreshUi();
            if (brightnessControl != null && brightnessControl.bar != null) brightnessControl.bar.setEnabled(false);
            if (saturationControl != null) saturationControl.refreshUi();
            if (contrastControl != null) contrastControl.refreshUi();
            if (hueControl != null) hueControl.refreshUi();
            if (sharpnessControl != null) sharpnessControl.refreshUi();
            if (autoWhiteBalanceCheck != null) autoWhiteBalanceCheck.setChecked(true);
            if (backlightCheck != null) backlightCheck.setChecked(true);
            if (powerlineSpinner != null) powerlineSpinner.setSelection(0);
            if (previewRateLabel != null) {
                previewRateLabel.setText("1080p预览实际目标：15 FPS（自动保护开启）");
            }
            if (controlStatusLabel != null) {
                controlStatusLabel.setText("相机实读：连接后显示");
                controlStatusLabel.setTextColor(0xffffc857);
            }
            updateParameterLabel();
        });
    }

    private void maybeAdjustControlledExposure(ByteBuffer frame) {
        if (!controlledAutoExposure || frame == null || camera == null || cameraHandler == null) return;
        long now = System.nanoTime();
        if (now - lastAutoExposureSampleNs < 800_000_000L || autoExposureWritePending.get()) return;
        lastAutoExposureSampleNs = now;
        if (frame.capacity() < WIDTH * HEIGHT) return;

        // Use both centre brightness and the fraction of near-white pixels. The
        // latter detects a large window before its highlights are irrecoverably
        // clipped. Sampling every 16 pixels keeps the 90 FPS callback lightweight.
        int centerSum = 0;
        int centerSamples = 0;
        int allSamples = 0;
        int highlightSamples = 0;
        int startX = WIDTH / 4;
        int endX = WIDTH * 3 / 4;
        int startY = HEIGHT / 4;
        int endY = HEIGHT * 3 / 4;
        for (int y = 0; y < HEIGHT; y += 16) {
            int row = y * WIDTH;
            for (int x = 0; x < WIDTH; x += 16) {
                int luma = frame.get(row + x) & 0xff;
                allSamples++;
                if (luma >= 245) highlightSamples++;
                if (x >= startX && x < endX && y >= startY && y < endY) {
                    centerSum += luma;
                    centerSamples++;
                }
            }
        }
        if (centerSamples == 0 || allSamples == 0) return;
        double measured = centerSum / (double) centerSamples;
        double highlightRatio = highlightSamples / (double) allSamples;
        smoothedCenterLuma = smoothedCenterLuma < 0
                ? measured : smoothedCenterLuma * 0.65 + measured * 0.35;
        smoothedHighlightRatio = smoothedHighlightRatio < 0
                ? highlightRatio : smoothedHighlightRatio * 0.55 + highlightRatio * 0.45;

        // This lens needs substantially more shadow lift than exposure alone can
        // provide. Keep the normal profile at 9.5 ms / Gamma 130 / brightness 0,
        // then add exposure, Gamma and finally brightness as the scene darkens.
        // Restore in reverse order when normal light returns.
        if (smoothedCenterLuma > 108.0 && brightnessControl != null &&
                brightnessControl.value > brightnessControl.defaultValue) {
            scheduleAutoPictureControl(brightnessControl,
                    Math.max(brightnessControl.defaultValue, brightnessControl.value - 4),
                    (int) Math.round(smoothedCenterLuma), now);
            return;
        }
        if (smoothedCenterLuma > 108.0 && gammaControl != null &&
                gammaControl.value > gammaControl.defaultValue) {
            scheduleAutoPictureControl(gammaControl,
                    Math.max(gammaControl.defaultValue, gammaControl.value - 10),
                    (int) Math.round(smoothedCenterLuma), now);
            return;
        }

        if (smoothedCenterLuma < 78.0 && exposureUnits >= 105 &&
                gammaControl != null && gammaControl.value < 170) {
            int requestedGamma = smoothedCenterLuma < 55.0
                    ? Math.max(160, gammaControl.value + 15)
                    : gammaControl.value + 10;
            scheduleAutoPictureControl(gammaControl,
                    Math.min(170, requestedGamma),
                    (int) Math.round(smoothedCenterLuma), now);
            return;
        }
        if (smoothedCenterLuma < 72.0 && exposureUnits >= 105 &&
                gammaControl != null && gammaControl.value >= 170 &&
                brightnessControl != null && brightnessControl.value < 12) {
            scheduleAutoPictureControl(brightnessControl,
                    Math.min(12, brightnessControl.value + (smoothedCenterLuma < 55.0 ? 6 : 4)),
                    (int) Math.round(smoothedCenterLuma), now);
            return;
        }

        int next = exposureUnits;
        if (smoothedCenterLuma < 88.0 && exposureUnits < 105) {
            next += smoothedCenterLuma < 60.0 ? 10 : 5;
        } else if (smoothedCenterLuma > 108.0 && exposureUnits > 95 &&
                (brightnessControl == null || brightnessControl.value <= brightnessControl.defaultValue) &&
                (gammaControl == null || gammaControl.value <= gammaControl.defaultValue)) {
            next -= 5;
        } else {
            return;
        }
        next = Math.max(95, Math.min(105, next));
        if (next == exposureUnits) return;
        exposureUnits = next;
        if (!autoExposureWritePending.compareAndSet(false, true)) return;
        final int requested = next;
        final int shownLuma = (int) Math.round(smoothedCenterLuma);
        cameraHandler.post(() -> {
            try {
                UVCCamera activeCamera = camera;
                if (activeCamera == null || !controlledAutoExposure) return;
                ControlResult result = applyControlsThroughActiveCamera(activeCamera, true, false);
                if (result.exposureOk) {
                    autoExposureFailures = 0;
                    actualExposureUnits = result.exposure;
                    ui.post(() -> {
                        if (exposureSeekBar != null) exposureSeekBar.setProgress(requested - 10);
                        if (exposureLabel != null) exposureLabel.setText(String.format(Locale.US,
                                "曝光时间：%.1f ms（暗处自动增亮）", requested / 10.0));
                        if (controlStatusLabel != null) {
                            controlStatusLabel.setText(String.format(Locale.US,
                                    "暗处自动增亮：曝光 %.1f ms · 中央亮度 %d/255\n" +
                                    "Gamma 维持 %d；仍偏暗时才逐步提高亮度",
                                    requested / 10.0, shownLuma,
                                    gammaControl == null ? 130 : gammaControl.value));
                            controlStatusLabel.setTextColor(0xff86efac);
                        }
                        updateParameterLabel();
                    });
                } else {
                    autoExposureFailures++;
                    if (autoExposureFailures >= 3) disableControlledExposureAfterFailure();
                }
            } catch (Throwable ignored) {
                autoExposureFailures++;
                if (autoExposureFailures >= 3) disableControlledExposureAfterFailure();
            } finally {
                autoExposureWritePending.set(false);
            }
        });
    }

    private void scheduleAutoPictureControl(HardwareControl control, int requested,
                                            int shownLuma, long now) {
        if (!controlledAutoExposure || control == null || requested == control.value ||
                now - lastAutoGammaChangeNs < 900_000_000L ||
                !autoExposureWritePending.compareAndSet(false, true)) return;
        lastAutoGammaChangeNs = now;
        control.value = requested;
        cameraHandler.post(() -> {
            try {
                UVCCamera activeCamera = camera;
                if (activeCamera == null || !controlledAutoExposure) return;
                Field blockField = UVCCamera.class.getDeclaredField("mCtrlBlock");
                blockField.setAccessible(true);
                USBMonitor.UsbControlBlock activeBlock =
                        (USBMonitor.UsbControlBlock) blockField.get(activeCamera);
                if (activeBlock == null) return;
                int controlInterface = findVideoControlInterface(activeBlock.getDevice());
                int processingUnit = findEntity(activeBlock.getRawDescriptors(), 0x05, false);
                int written = setUvcRetry(activeBlock.getConnection(), controlInterface,
                        processingUnit, control.selector, littleEndian(requested, 2));
                try { Thread.sleep(35); } catch (InterruptedException ignored) { }
                byte[] actualBytes = getUvcRequest(activeBlock.getConnection(), controlInterface,
                        processingUnit, control.selector, 0x81, 2, 200);
                int actual = control.signed
                        ? readSignedLittleEndian(actualBytes) : readLittleEndian(actualBytes);
                boolean ok = written == 2 && actual == requested;
                if (!ok) control.value = actualBytes == null ? control.defaultValue
                        : Math.max(control.min, Math.min(control.max, actual));
                ui.post(() -> {
                    control.refreshUi();
                    control.bar.setEnabled(false);
                    if (controlStatusLabel != null) {
                        controlStatusLabel.setText(ok
                                ? "暗处已到曝光上限10.5 ms，自动" + control.name + " " + requested +
                                " · 中央亮度 " + shownLuma + "/255"
                                : "相机未接受自动" + control.name +
                                "；曝光仍限制在10.5 ms，视频流未重启");
                        controlStatusLabel.setTextColor(ok ? 0xff86efac : 0xffffc857);
                    }
                });
            } catch (Throwable ignored) {
                control.value = control.defaultValue;
            } finally {
                autoExposureWritePending.set(false);
            }
        });
    }

    private void disableControlledExposureAfterFailure() {
        controlledAutoExposure = false;
        ui.post(() -> {
            if (autoExposureCheck != null) autoExposureCheck.setChecked(false);
            if (exposureSeekBar != null) exposureSeekBar.setEnabled(true);
            if (gammaControl != null && gammaControl.bar != null) gammaControl.bar.setEnabled(true);
            if (brightnessControl != null && brightnessControl.bar != null) brightnessControl.bar.setEnabled(true);
            if (controlStatusLabel != null) {
                controlStatusLabel.setText("相机连续拒绝自动曝光调整，已安全切回手动；视频流未重启");
                controlStatusLabel.setTextColor(0xffffc857);
            }
            updateParameterLabel();
        });
    }

    private void resetHardwareControl(HardwareControl control) {
        if (control != null) control.value = control.defaultValue;
    }

    private void detectGainControl(UsbDeviceConnection connection, int controlInterface,
                                   int processingUnit) {
        if (gainControl == null || processingUnit <= 0) return;
        byte[] infoBytes = getUvcRequest(connection, controlInterface, processingUnit,
                0x04, 0x86, 1, 200);
        byte[] minBytes = getUvcRequest(connection, controlInterface, processingUnit,
                0x04, 0x82, 2, 200);
        byte[] maxBytes = getUvcRequest(connection, controlInterface, processingUnit,
                0x04, 0x83, 2, 200);
        byte[] currentBytes = getUvcRequest(connection, controlInterface, processingUnit,
                0x04, 0x81, 2, 200);
        int info = readLittleEndian(infoBytes);
        int min = readLittleEndian(minBytes);
        int max = readLittleEndian(maxBytes);
        int current = readLittleEndian(currentBytes);
        boolean available = infoBytes != null && minBytes != null && maxBytes != null &&
                currentBytes != null && (info & 0x03) == 0x03 && max > min && max - min <= 4096;
        gainSupported = available;
        gainControl.supported = available;
        if (available) {
            gainControl.min = min;
            gainControl.max = max;
            gainControl.value = Math.max(min, Math.min(max, current));
        }
        ui.post(() -> {
            if (available) {
                gainControl.label.setTextColor(Color.WHITE);
                gainControl.bar.setVisibility(View.VISIBLE);
                gainControl.bar.setEnabled(true);
                gainControl.refreshUi();
            } else {
                gainControl.label.setText("ISO/增益：当前相机未通过标准UVC开放");
                gainControl.label.setTextColor(0xffffc857);
                gainControl.bar.setVisibility(View.GONE);
                gainControl.bar.setEnabled(false);
            }
            updateParameterLabel();
        });
    }

    private void updateHardwareControlRanges(UsbDeviceConnection connection, int controlInterface,
                                             int processingUnit) {
        updateHardwareControlRange(connection, controlInterface, processingUnit, gammaControl);
        updateHardwareControlRange(connection, controlInterface, processingUnit, brightnessControl);
        updateHardwareControlRange(connection, controlInterface, processingUnit, saturationControl);
        updateHardwareControlRange(connection, controlInterface, processingUnit, contrastControl);
        updateHardwareControlRange(connection, controlInterface, processingUnit, hueControl);
        updateHardwareControlRange(connection, controlInterface, processingUnit, sharpnessControl);
    }

    private void updateHardwareControlRange(UsbDeviceConnection connection, int controlInterface,
                                            int processingUnit, HardwareControl control) {
        if (control == null) return;
        byte[] minBytes = getUvcRequest(connection, controlInterface, processingUnit,
                control.selector, 0x82, 2, 200);
        byte[] maxBytes = getUvcRequest(connection, controlInterface, processingUnit,
                control.selector, 0x83, 2, 200);
        int min = control.signed ? readSignedLittleEndian(minBytes) : readLittleEndian(minBytes);
        int max = control.signed ? readSignedLittleEndian(maxBytes) : readLittleEndian(maxBytes);
        if (minBytes != null && maxBytes != null && max > min && max - min <= 4096) {
            control.min = min;
            control.max = max;
            control.value = Math.max(min, Math.min(max, control.value));
            ui.post(control::refreshUi);
        }
    }

    private void applyPictureControlAsync(HardwareControl control) {
        if (control == null || !control.supported) {
            showToast("当前相机没有开放这个硬件控制");
            return;
        }
        applyProcessingUnitControlAsync(control.selector, 2, control.value,
                control.signed, control.name);
    }

    private void applyProcessingUnitControlAsync(int selector, int length, int value,
                                                 boolean signed, String name) {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            try {
                UVCCamera activeCamera = camera;
                if (activeCamera == null) {
                    showToast(name + "将在连接相机后生效");
                    return;
                }
                Field blockField = UVCCamera.class.getDeclaredField("mCtrlBlock");
                blockField.setAccessible(true);
                USBMonitor.UsbControlBlock activeBlock =
                        (USBMonitor.UsbControlBlock) blockField.get(activeCamera);
                if (activeBlock == null) throw new IllegalStateException("活动USB连接为空");
                int controlInterface = findVideoControlInterface(activeBlock.getDevice());
                int processingUnit = findEntity(activeBlock.getRawDescriptors(), 0x05, false);
                int written = setUvcRetry(activeBlock.getConnection(), controlInterface,
                        processingUnit, selector, littleEndian(value, length));
                try { Thread.sleep(35); } catch (InterruptedException ignored) { }
                byte[] readBytes = getUvcRequest(activeBlock.getConnection(), controlInterface,
                        processingUnit, selector, 0x81, length, 200);
                int actual = signed ? readSignedLittleEndian(readBytes) : readLittleEndian(readBytes);
                boolean ok = written == length && readBytes != null && actual == value;
                ui.post(() -> {
                    if (controlStatusLabel != null) {
                        controlStatusLabel.setText(name + "：请求 " + value + "，相机实读 " +
                                (readBytes == null ? "失败" : actual) +
                                "\n" + (ok ? "已生效；视频流保持运行" :
                                "相机未接受该值；未重启视频流"));
                        controlStatusLabel.setTextColor(ok ? 0xff86efac : 0xffffc857);
                    }
                });
                showToast(ok ? name + " 已生效：" + actual : name + " 未被相机接受");
            } catch (Throwable error) {
                showToast(name + " 调整失败；视频流未重启");
            }
        });
    }

    private void applySelectedControlsAsync(boolean changeExposure, boolean changeWhiteBalance) {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            UVCCamera activeCamera = camera;
            if (activeCamera != null) {
                ControlResult result = applyControlsThroughActiveCamera(
                        activeCamera, changeExposure, changeWhiteBalance);
                showControlResult(result);
                if (result.exposureOk || result.whiteBalanceOk) showToast(result.summary());
                else showToast("相机拒绝了实时调整；视频流保持连接，重新插入后仍恢复默认设置");
            }
        });
    }

    private ControlResult applyControlsThroughActiveCamera(UVCCamera targetCamera,
                                                           boolean changeExposure,
                                                           boolean changeWhiteBalance) {
        try {
            Field pointerField = UVCCamera.class.getDeclaredField("mNativePtr");
            pointerField.setAccessible(true);
            long pointer = pointerField.getLong(targetCamera);
            if (pointer == 0) throw new IllegalStateException("活动相机句柄为空");

            Method setExposure = privateNative("nativeSetExposure", long.class, int.class);
            Method getExposure = privateNative("nativeGetExposure", long.class);
            Method setWb = privateNative("nativeSetWhiteBlance", long.class, int.class);
            Method getWb = privateNative("nativeGetWhiteBlance", long.class);
            int exposureSet = 0;
            int wbSet = 0;
            int readExposure;
            int readWb;
            int readPowerline;
            synchronized (targetCamera) {
                // Manual/auto mode is configured once before preview starts. Re-sending those
                // switches while this firmware streams resets its sensor and causes a black view.
                if (changeExposure) {
                    exposureSet = (Integer) setExposure.invoke(null, pointer, exposureUnits);
                }
                if (changeWhiteBalance) {
                    wbSet = (Integer) setWb.invoke(null, pointer, whiteBalanceKelvin);
                }
                try { Thread.sleep(35); } catch (InterruptedException ignored) { }
                readExposure = (Integer) getExposure.invoke(null, pointer);
                readWb = (Integer) getWb.invoke(null, pointer);
                readPowerline = targetCamera.getPowerlineFrequency();
            }
            boolean exposureOk = !changeExposure || (exposureSet == 0 && readExposure > 0);
            boolean wbOk = !changeWhiteBalance || (wbSet == 0 && readWb > 0);
            if ((changeExposure && !exposureOk) || (changeWhiteBalance && !wbOk)) {
                // This camera omits these standard controls from libuvc's parsed
                // support mask even though its endpoint accepts them. Use the same
                // active USB connection and write only the changed value. Never
                // resend AE/AWB mode switches here because those reset its stream.
                ControlResult direct = applyOneControlThroughActiveUsb(
                        targetCamera, changeExposure, changeWhiteBalance);
                if ((changeExposure && direct.exposureOk) ||
                        (changeWhiteBalance && direct.whiteBalanceOk)) return direct;
            }
            return new ControlResult(exposureOk, wbOk, readExposure, readWb, readPowerline,
                    "视频流内安全控制 [EXP " + (changeExposure ? exposureSet : "未改") +
                            ", WB " + (changeWhiteBalance ? wbSet : "未改") + "]");
        } catch (Throwable error) {
            return new ControlResult(false, false, -1, -1, -1,
                    "安全控制失败（未重启视频）：" + error.getClass().getSimpleName() + "：" + error.getMessage());
        }
    }

    private ControlResult applyOneControlThroughActiveUsb(UVCCamera targetCamera,
                                                          boolean changeExposure,
                                                          boolean changeWhiteBalance) {
        try {
            Field blockField = UVCCamera.class.getDeclaredField("mCtrlBlock");
            blockField.setAccessible(true);
            USBMonitor.UsbControlBlock activeBlock =
                    (USBMonitor.UsbControlBlock) blockField.get(targetCamera);
            if (activeBlock == null) throw new IllegalStateException("活动USB连接为空");
            UsbDeviceConnection connection = activeBlock.getConnection();
            byte[] descriptors = activeBlock.getRawDescriptors();
            int controlInterface = findVideoControlInterface(activeBlock.getDevice());
            int cameraTerminal = findEntity(descriptors, 0x02, true);
            int processingUnit = findEntity(descriptors, 0x05, false);
            int exposureSet = changeExposure
                    ? setUvc(connection, controlInterface, cameraTerminal, 0x04,
                            littleEndian(exposureUnits, 4)) : 0;
            int wbSet = changeWhiteBalance
                    ? setUvc(connection, controlInterface, processingUnit, 0x0a,
                            littleEndian(whiteBalanceKelvin, 2)) : 0;
            try { Thread.sleep(35); } catch (InterruptedException ignored) { }
            int readExposure = readLittleEndian(
                    getUvc(connection, controlInterface, cameraTerminal, 0x04, 4));
            int readWb = readLittleEndian(
                    getUvc(connection, controlInterface, processingUnit, 0x0a, 2));
            int readPowerline = readLittleEndian(
                    getUvc(connection, controlInterface, processingUnit, 0x05, 1));
            boolean exposureOk = !changeExposure ||
                    (exposureSet == 4 && readExposure == exposureUnits);
            boolean wbOk = !changeWhiteBalance ||
                    (wbSet == 2 && readWb == whiteBalanceKelvin);
            return new ControlResult(exposureOk, wbOk, readExposure, readWb, readPowerline,
                    "活动USB单项控制 [EXP " + (changeExposure ? exposureSet : "未改") +
                            ", WB " + (changeWhiteBalance ? wbSet : "未改") + "]");
        } catch (Throwable error) {
            return new ControlResult(false, false, -1, -1, -1,
                    "活动USB单项控制失败（视频未重启）：" + error.getClass().getSimpleName() +
                            "：" + error.getMessage());
        }
    }

    private void showControlResult(ControlResult result) {
        actualExposureUnits = result.exposure;
        actualWhiteBalanceKelvin = result.whiteBalance;
        actualPowerline = result.powerline;
        ui.post(() -> {
            if (controlStatusLabel == null) return;
            controlStatusLabel.setText(result.summary() + " · " + gainSummary() +
                    "\n通道：" + result.channel);
            controlStatusLabel.setTextColor(result.exposureOk && result.whiteBalanceOk
                    ? 0xff86efac : 0xffffc857);
        });
    }

    private static final class ControlResult {
        final boolean exposureOk;
        final boolean whiteBalanceOk;
        final int exposure;
        final int whiteBalance;
        final int powerline;
        final String channel;

        ControlResult(boolean exposureOk, boolean whiteBalanceOk, int exposure,
                      int whiteBalance, int powerline, String channel) {
            this.exposureOk = exposureOk;
            this.whiteBalanceOk = whiteBalanceOk;
            this.exposure = exposure;
            this.whiteBalance = whiteBalance;
            this.powerline = powerline;
            this.channel = channel;
        }

        String summary() {
            String exposureText = exposure > 0
                    ? String.format(Locale.US, "%.1f ms", exposure / 10.0) : "失败";
            String wbText = whiteBalance > 0 ? whiteBalance + " K" : "自动";
            String hzText = powerline == 1 ? "50 Hz" : (powerline == 2 ? "60 Hz" :
                    (powerline == 3 ? "自动" : "自动/未知"));
            return "相机实读：曝光 " + exposureText + " · 白平衡 " + wbText + " · " + hzText;
        }
    }

    private int findEntity(byte[] data, int subtype, boolean requireCameraTerminal) {
        if (data == null) return -1;
        for (int p = 0; p + 5 < data.length;) {
            int len = data[p] & 0xff;
            if (len < 3 || p + len > data.length) { p++; continue; }
            if ((data[p + 1] & 0xff) == 0x24 && (data[p + 2] & 0xff) == subtype) {
                if (!requireCameraTerminal || (len > 5 && (data[p + 4] & 0xff) == 0x01 && (data[p + 5] & 0xff) == 0x02)) {
                    return data[p + 3] & 0xff;
                }
            }
            p += len;
        }
        return -1;
    }

    private int setUvc(UsbDeviceConnection connection, int controlInterface, int entity,
                       int selector, byte[] value) {
        int index = (entity << 8) | (controlInterface & 0xff);
        return connection.controlTransfer(0x21, 0x01, selector << 8, index,
                value, value.length, 500);
    }

    private int setUvcRetry(UsbDeviceConnection connection, int controlInterface, int entity,
                            int selector, byte[] value) {
        int result = -1;
        for (int attempt = 0; attempt < 3; attempt++) {
            result = setUvc(connection, controlInterface, entity, selector, value);
            if (result == value.length) return result;
            try { Thread.sleep(40); } catch (InterruptedException ignored) { }
        }
        return result;
    }

    private byte[] getUvc(UsbDeviceConnection connection, int controlInterface, int entity,
                          int selector, int length) {
        if (entity <= 0) return null;
        byte[] value = new byte[length];
        int index = (entity << 8) | (controlInterface & 0xff);
        int result = connection.controlTransfer(0xa1, 0x81, selector << 8, index,
                value, value.length, 500);
        return result == length ? value : null;
    }

    private int readLittleEndian(byte[] bytes) {
        if (bytes == null) return -1;
        int value = 0;
        for (int i = 0; i < bytes.length; i++) value |= (bytes[i] & 0xff) << (8 * i);
        return value;
    }

    private int readSignedLittleEndian(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return -1;
        int value = readLittleEndian(bytes);
        int bits = bytes.length * 8;
        if (bits < 32 && (value & (1 << (bits - 1))) != 0) value -= 1 << bits;
        return value;
    }

    private byte[] littleEndian(int value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (value >> (8 * i));
        return bytes;
    }

    private final Runnable fpsTicker = new Runnable() {
        @Override public void run() {
            long now = System.nanoTime();
            long frames = frameCounter.get();
            if (previousTickNs > 0) {
                double seconds = (now - previousTickNs) / 1_000_000_000.0;
                double fps = (frames - previousFrames) / seconds;
                measuredInputFps = fps;
                if (recording) updateAdaptivePreview(fps);
                EncoderRecorder active = recorder;
                long recordedFrames = active != null ? active.queuedFrames : 0;
                double fileFps = recording ? (recordedFrames - previousRecordedFrames) / seconds : 0.0;
                previousRecordedFrames = recordedFrames;
                fpsLabel.setText(String.format(Locale.US, "实收 %.1f FPS%s", fps,
                        recording ? String.format(Locale.US, " · 文件实写 %.1f/%d · 预览 %d · %s",
                                fileFps, selectedRecordFps, effectivePreviewFps, thermalLabel()) : ""));
                fpsLabel.setTextColor(fps >= 89.0 ? 0xff86efac : (fps >= 85.0 ? 0xffffc857 : 0xffff6b6b));
            }
            previousFrames = frames;
            previousTickNs = now;
            ui.postDelayed(this, 1000);
        }
    };

    private void updateAdaptivePreview(double fps) {
        int thermal = thermalStatus();
        if (fps < 88.0) slowFpsTicks = Math.min(10, slowFpsTicks + 1);
        else slowFpsTicks = Math.max(0, slowFpsTicks - 1);

        int chosen = selectedPreviewFps;
        String reason = "手动设定";
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE || slowFpsTicks >= 5) {
            chosen = Math.min(chosen, 5);
            reason = "正在强力保护 90 帧采集";
        } else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE || slowFpsTicks >= 2) {
            chosen = Math.min(chosen, 10);
            reason = "正在保护 90 帧采集";
        }
        effectivePreviewFps = chosen;
        final int shown = chosen;
        final String shownReason = reason;
        ui.post(() -> {
            if (previewRateLabel != null) {
                previewRateLabel.setText("1080p预览实际目标：" + shown + " FPS（" + shownReason + "）");
                previewRateLabel.setTextColor(shown == selectedPreviewFps ? 0xff86efac : 0xffffc857);
            }
        });
    }

    private int thermalStatus() {
        if (android.os.Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE;
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return power.getCurrentThermalStatus();
        } catch (Throwable ignored) {
            return PowerManager.THERMAL_STATUS_NONE;
        }
    }

    private String thermalLabel() {
        if (android.os.Build.VERSION.SDK_INT < 29) return "温控未知";
        try {
            int level = thermalStatus();
            switch (level) {
                case PowerManager.THERMAL_STATUS_NONE: return "温控正常";
                case PowerManager.THERMAL_STATUS_LIGHT: return "温控轻度";
                case PowerManager.THERMAL_STATUS_MODERATE: return "温控中度";
                case PowerManager.THERMAL_STATUS_SEVERE: return "温控严重";
                case PowerManager.THERMAL_STATUS_CRITICAL: return "温控临界";
                case PowerManager.THERMAL_STATUS_EMERGENCY: return "温控紧急";
                case PowerManager.THERMAL_STATUS_SHUTDOWN: return "温控关机";
                default: return "温控未知";
            }
        } catch (Throwable ignored) {
            return "温控未知";
        }
    }

    private void show(String text) {
        ui.post(() -> status.setText(text));
    }

    private void showToast(String text) {
        ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    @Override protected void onStart() {
        super.onStart();
        if (usbMonitor != null) {
            try {
                if (!usbMonitor.isRegistered()) usbMonitor.register();
            } catch (Throwable error) {
                show("USB 监听启动失败：\n" + error);
            }
        }
    }

    @Override protected void onStop() {
        stopCamera();
        if (usbMonitor != null && usbMonitor.isRegistered()) usbMonitor.unregister();
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (permissionReceiverRegistered) {
            try { unregisterReceiver(permissionReceiver); } catch (Throwable ignored) { }
            permissionReceiverRegistered = false;
        }
        if (usbMonitor != null) {
            try { usbMonitor.destroy(); } catch (Throwable ignored) { }
        }
        if (cameraThread != null) cameraThread.quitSafely();
        if (previewThread != null) previewThread.quitSafely();
        super.onDestroy();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        openWhenReady();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopCamera();
    }

    /**
     * Creates a full 1920x1080 monitor image from the same fixed-resolution
     * callback used by recording. Only the display cadence changes; resolution
     * and crop never change during the session.
     */
    private final class PreviewRenderer {
        private static final int PREVIEW_WIDTH = WIDTH;
        private static final int PREVIEW_HEIGHT = HEIGHT;
        private final byte[] smallYuv = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 3 / 2];
        private final int[] pixels = new int[PREVIEW_WIDTH * PREVIEW_HEIGHT];
        private final AtomicBoolean pending = new AtomicBoolean();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        private volatile long lastFrameNs;

        void reset() {
            lastFrameNs = 0;
            pending.set(false);
        }

        void offer(ByteBuffer frame, int requestedFps) {
            if (frame == null || requestedFps <= 0 || previewHandler == null) return;
            long now = System.nanoTime();
            long interval = 1_000_000_000L / requestedFps;
            if (now - lastFrameNs < interval || !pending.compareAndSet(false, true)) return;
            lastFrameNs = now;
            try {
                if (frame.capacity() < WIDTH * HEIGHT * 3 / 2) {
                    pending.set(false);
                    return;
                }
                ByteBuffer source = frame.duplicate();
                source.rewind();
                source.get(smallYuv, 0, smallYuv.length);
                previewHandler.post(this::draw);
            } catch (Throwable ignored) {
                pending.set(false);
            }
        }

        private void convertSmallYuvToRgb() {
            final int yPlaneSize = PREVIEW_WIDTH * PREVIEW_HEIGHT;
            final int[] yLut = lumaCurve;
            final int[] uLut = uCurve;
            final int[] vLut = vCurve;
            int out = 0;
            for (int y = 0; y < PREVIEW_HEIGHT; y++) {
                int yRow = y * PREVIEW_WIDTH;
                int uvRow = yPlaneSize + (y >> 1) * PREVIEW_WIDTH;
                for (int x = 0; x < PREVIEW_WIDTH; x++) {
                        // UVC MJPEG is JPEG-range YCbCr. Keep the preview on the
                        // same full-range BT.601 interpretation as the recording.
                        int yy = yLut[smallYuv[yRow + x] & 0xff];
                        int uv = uvRow + (x & ~1);
                        int u = uLut[smallYuv[uv] & 0xff] - 128;
                        int v = vLut[smallYuv[uv + 1] & 0xff] - 128;
                        int c = 256 * yy;
                        int r = (c + 359 * v + 128) >> 8;
                        int g = (c - 88 * u - 183 * v + 128) >> 8;
                        int b = (c + 454 * u + 128) >> 8;
                        r = r < 0 ? 0 : (r > 255 ? 255 : r);
                        g = g < 0 ? 0 : (g > 255 ? 255 : g);
                        b = b < 0 ? 0 : (b > 255 ? 255 : b);
                        pixels[out++] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        private void draw() {
            Canvas canvas = null;
            try {
                if (!surfaceReady || correctedPreview == null || !correctedPreview.isAvailable()) return;
                convertSmallYuvToRgb();
                if (bitmap == null) bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
                bitmap.setPixels(pixels, 0, PREVIEW_WIDTH, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT);
                canvas = correctedPreview.lockCanvas();
                if (canvas == null) return;
                canvas.drawColor(Color.BLACK);
                int cw = canvas.getWidth();
                int ch = canvas.getHeight();
                float scale = Math.min(cw / (float) PREVIEW_WIDTH, ch / (float) PREVIEW_HEIGHT);
                int dw = Math.round(PREVIEW_WIDTH * scale);
                int dh = Math.round(PREVIEW_HEIGHT * scale);
                Rect destination = new Rect((cw - dw) / 2, (ch - dh) / 2, (cw + dw) / 2, (ch + dh) / 2);
                canvas.drawBitmap(bitmap, null, destination, paint);
            } catch (Throwable ignored) {
            } finally {
                if (canvas != null) {
                    try { correctedPreview.unlockCanvasAndPost(canvas); } catch (Throwable ignored) { }
                }
                pending.set(false);
            }
        }
    }

    private final class EncoderRecorder {
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private ParcelFileDescriptor outputFd;
        private Uri outputUri;
        private Thread drainThread;
        private Thread inputThread;
        private final ArrayBlockingQueue<byte[]> freeFrames = new ArrayBlockingQueue<>(4);
        private final ArrayBlockingQueue<byte[]> readyFrames = new ArrayBlockingQueue<>(4);
        private volatile boolean finishing;
        private volatile boolean eosQueued;
        private int encoderFps;
        private int encoderBitRate;
        private volatile long queuedFrames;
        private long droppedFrames;
        private long offeredFrames;
        private long selectedFrames;
        private int samplingAccumulator;
        private long firstOfferUs = -1;
        private long lastOfferUs = -1;
        private int track = -1;
        private boolean muxerStarted;

        void start() throws Exception {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "UVC90_" + stamp + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/UVC90");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IllegalStateException("无法创建录像文件");
                outputFd = getContentResolver().openFileDescriptor(outputUri, "rw");
                muxer = new MediaMuxer(outputFd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UVC90");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建录像目录");
                muxer = new MediaMuxer(new File(dir, "UVC90_" + stamp + ".mp4").getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            encoderFps = selectedRecordFps;
            format.setInteger(MediaFormat.KEY_FRAME_RATE, encoderFps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setFloat(MediaFormat.KEY_OPERATING_RATE, encoderFps);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            encoderBitRate = BIT_RATE;
            int frameBytes = WIDTH * HEIGHT * 3 / 2;
            for (int i = 0; i < 4; i++) freeFrames.offer(new byte[frameBytes]);
            drainThread = new Thread(this::drain, "uvc-encoder");
            drainThread.start();
            inputThread = new Thread(this::feedEncoder, "uvc-color-input");
            inputThread.start();
        }

        void offer(ByteBuffer frame) {
            if (finishing || encoder == null || frame == null) return;
            try {
                long arrivalUs = System.nanoTime() / 1000L;
                offeredFrames++;
                if (firstOfferUs < 0) firstOfferUs = arrivalUs;
                lastOfferUs = arrivalUs;
                samplingAccumulator += encoderFps;
                if (samplingAccumulator < CAMERA_FPS) return;
                samplingAccumulator -= CAMERA_FPS;
                byte[] copy = freeFrames.poll();
                if (copy == null) {
                    droppedFrames++;
                    return;
                }
                frame.rewind();
                if (frame.remaining() < copy.length) {
                    freeFrames.offer(copy);
                    droppedFrames++;
                    return;
                }
                frame.get(copy, 0, copy.length);
                if (!readyFrames.offer(copy)) {
                    freeFrames.offer(copy);
                    droppedFrames++;
                    return;
                }
                selectedFrames++;
            } catch (Throwable error) {
                droppedFrames++;
            }
        }

        private void feedEncoder() {
            while (!finishing || !readyFrames.isEmpty()) {
                byte[] frame = null;
                try {
                    frame = readyFrames.poll(20, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    int index = encoder.dequeueInputBuffer(10_000);
                    if (index < 0) {
                        droppedFrames++;
                        continue;
                    }
                    ByteBuffer input = encoder.getInputBuffer(index);
                    if (input == null || input.remaining() < frame.length) {
                        if (input != null) input.clear();
                        encoder.queueInputBuffer(index, 0, 0,
                                queuedFrames * 1_000_000L / encoderFps, 0);
                        droppedFrames++;
                        continue;
                    }
                    input.clear();
                    input.put(frame);
                    applySoftwareColorToEncoderInput(input, frame.length);
                    long ptsUs = queuedFrames * 1_000_000L / encoderFps;
                    encoder.queueInputBuffer(index, 0, frame.length, ptsUs, 0);
                    queuedFrames++;
                } catch (InterruptedException ignored) {
                    if (finishing) break;
                } catch (Throwable error) {
                    droppedFrames++;
                } finally {
                    if (frame != null) freeFrames.offer(frame);
                }
            }
        }

        private void applySoftwareColorToEncoderInput(ByteBuffer input, int size) {
            if (brightnessLift == 0 && redBalance == 0 && greenBalance == 0 &&
                    blueBalance == 0 && saturationAdjust == 0) return;
            int[] yLut = lumaCurve;
            int[] localULut = uCurve;
            int[] localVLut = vCurve;
            int yEnd = Math.min(WIDTH * HEIGHT, size);
            for (int i = 0; i + 3 < yEnd; i += 4) {
                int value = input.getInt(i);
                int adjusted = yLut[value >>> 24] << 24
                        | yLut[(value >>> 16) & 0xff] << 16
                        | yLut[(value >>> 8) & 0xff] << 8
                        | yLut[value & 0xff];
                input.putInt(i, adjusted);
            }
            if (size <= yEnd) return;
            int start = yEnd;
            int end = size - 3;
            for (int i = start; i < end; i += 4) {
                int value = input.getInt(i);
                int adjusted = localULut[value >>> 24] << 24
                        | localVLut[(value >>> 16) & 0xff] << 16
                        | localULut[(value >>> 8) & 0xff] << 8
                        | localVLut[value & 0xff];
                input.putInt(i, adjusted);
            }
        }

        private void drain() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (true) {
                    int index = encoder.dequeueOutputBuffer(info, 10_000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } else if (index >= 0) {
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if (data != null && info.size > 0 && muxerStarted) {
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            muxer.writeSampleData(track, data, info);
                        }
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(index, false);
                        if (eos) break;
                    } else if (finishing && index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Continue until the explicit end-of-stream buffer arrives.
                    }
                }
            } catch (Throwable error) {
                showToast("录像写入异常：" + error.getMessage());
            }
        }

        void finish() {
            finishing = true;
            try { if (inputThread != null) inputThread.join(3000); }
            catch (InterruptedException ignored) { }
            long deadline = System.nanoTime() + 500_000_000L;
            while (!eosQueued && System.nanoTime() < deadline) {
                try {
                    int index = encoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        long eosPtsUs = queuedFrames * 1_000_000L / Math.max(1, encoderFps);
                        encoder.queueInputBuffer(index, 0, 0, eosPtsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                } catch (Throwable ignored) { break; }
            }
            try { if (drainThread != null) drainThread.join(3000); } catch (InterruptedException ignored) { }
            release(true);
            double receivedFps = (offeredFrames > 1 && lastOfferUs > firstOfferUs)
                    ? (offeredFrames - 1) * 1_000_000.0 / (lastOfferUs - firstOfferUs) : 0.0;
            String fpsText = String.format(Locale.US, "%.2f", receivedFps);
            double encodedFps = (offeredFrames > 0) ? receivedFps * queuedFrames / offeredFrames : 0.0;
            boolean sourceGood = encoderFps < 90 || receivedFps >= 89.0;
            boolean encodeGood = droppedFrames == 0 && encodedFps >= encoderFps - 1.0;
            if (sourceGood && encodeGood) {
                showToast("录像达标：相机实收 " + fpsText + " FPS，文件约 " +
                        String.format(Locale.US, "%.2f", encodedFps) + " FPS，编码丢帧 0");
            } else {
                showToast("录像不达标：相机实收 " + fpsText + " FPS，选择 " + selectedFrames +
                        " 帧，编码 " + queuedFrames + " 帧，编码器另丢 " + droppedFrames + " 帧");
            }
        }

        void abort() {
            finishing = true;
            if (inputThread != null) inputThread.interrupt();
            try { if (inputThread != null) inputThread.join(500); }
            catch (InterruptedException ignored) { }
            release(false);
        }

        private void release(boolean save) {
            try { if (encoder != null) encoder.stop(); } catch (Throwable ignored) { }
            try { if (encoder != null) encoder.release(); } catch (Throwable ignored) { }
            try { if (muxerStarted && muxer != null) muxer.stop(); } catch (Throwable ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
            try { if (outputFd != null) outputFd.close(); } catch (Throwable ignored) { }
            if (android.os.Build.VERSION.SDK_INT >= 29 && outputUri != null) {
                if (save) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outputUri, done, null, null);
                    showToast("录像已保存到 Movies/UVC90");
                } else {
                    getContentResolver().delete(outputUri, null, null);
                }
            }
        }
    }
}
