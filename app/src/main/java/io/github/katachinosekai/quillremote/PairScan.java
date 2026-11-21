package io.github.katachinosekai.quillremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public class PairScan extends Activity implements SurfaceHolder.Callback {
    static final int CAMERA_REQ = 21;

    SurfaceView view;
    TextView hint;
    FrameLayout root;
    HandlerThread thread;
    Handler bg;
    CameraDevice cam;
    CameraCaptureSession session;
    ImageReader reader;
    Size size;
    int sensor;
    boolean surface;
    volatile boolean done;
    long wrongAt;
    final QRCodeReader qr = new QRCodeReader();
    final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff1f1f1d);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setBackgroundColor(0xcc1f1f1d);
        int pad = dp(14);
        bar.setPadding(pad, pad, pad, pad);
        hint = new TextView(this);
        hint.setText("Point the camera at the QR code from the Devices button in the Quill Desktop top bar");
        hint.setTextColor(0xffeceae3);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        bar.addView(hint);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> fit());
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return in;
        });
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
        }
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req != CAMERA_REQ) {
            return;
        }
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            open();
        } else {
            hint.setText("Quill Android needs the camera to scan the pairing code. Allow it in Android settings, or use Paste pairing link.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("pairscan");
        thread.start();
        bg = new Handler(thread.getLooper());
        open();
    }

    @Override
    protected void onPause() {
        close();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surface = true;
        open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surface = false;
        close();
    }

    void fit() {
        if (size == null) {
            return;
        }
        int rot = 90 * getDisplay().getRotation();
        boolean swap = (sensor - rot + 360) % 180 != 0;
        float pw = swap ? size.getHeight() : size.getWidth();
        float ph = swap ? size.getWidth() : size.getHeight();
        int aw = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int ah = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (aw <= 0 || ah <= 0) {
            return;
        }
        float k = Math.min(aw / pw, ah / ph);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        int w = Math.round(pw * k), h = Math.round(ph * k);
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            view.setLayoutParams(lp);
        }
    }

    void open() {
        if (cam != null || !surface || bg == null || done
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer face = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (id == null || (face != null && face == CameraCharacteristics.LENS_FACING_BACK)) {
                    id = c;
                    if (face != null && face == CameraCharacteristics.LENS_FACING_BACK) {
                        break;
                    }
                }
            }
            if (id == null) {
                hint.setText("No camera found. Use Paste pairing link instead.");
                return;
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensor = so == null ? 0 : so;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            size = pick(map.getOutputSizes(ImageFormat.YUV_420_888));
            view.getHolder().setFixedSize(size.getWidth(), size.getHeight());
            fit();
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    cam = d;
                    start();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    cam = null;
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    cam = null;
                    runOnUiThread(() -> hint.setText("The camera is busy or unavailable. Close other camera apps and try again."));
                }
            }, bg);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            hint.setText("The camera is unavailable. Use Paste pairing link instead.");
        }
    }

    static Size pick(Size[] all) {
        Size best = all[0];
        long bestArea = 0;
        for (Size s : all) {
            long a = (long) s.getWidth() * s.getHeight();
            if (a <= 1280L * 960 && a > bestArea) {
                best = s;
                bestArea = a;
            }
        }
        return best;
    }

    void start() {
        try {
            Surface preview = view.getHolder().getSurface();
            CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(preview);
            rb.addTarget(reader.getSurface());
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            cam.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(new OutputConfiguration(preview), new OutputConfiguration(reader.getSurface())),
                    bg::post, new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(rb.build(), null, bg);
                            } catch (CameraAccessException | IllegalStateException ignored) {
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> hint.setText("The camera could not start. Use Paste pairing link instead."));
                        }
                    }));
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> hint.setText("The camera could not start. Use Paste pairing link instead."));
        }
    }

    void frame(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (img == null) {
            return;
        }
        String text = null;
        try {
            if (done) {
                return;
            }
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int stride = y.getRowStride();
            byte[] data = new byte[w * h];
            for (int row = 0; row < h; row++) {
                buf.position(row * stride);
                buf.get(data, row * w, w);
            }
            text = decode(new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false));
        } catch (RuntimeException ignored) {
        } finally {
            img.close();
        }
        if (text == null) {
            return;
        }
        Uri u = Uri.parse(text.trim());
        if (!"quillpair".equals(u.getScheme())) {
            long now = System.currentTimeMillis();
            if (now - wrongAt > 3000) {
                wrongAt = now;
                runOnUiThread(() -> hint.setText("That QR code isn't a Quill Desktop pairing code. Use the one from the Devices button in the Quill Desktop top bar."));
            }
            return;
        }
        done = true;
        runOnUiThread(() -> {
            setResult(RESULT_OK, new Intent().setData(u));
            finish();
        });
    }

    String decode(PlanarYUVLuminanceSource src) {
        try {
            Result res = qr.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            return res.getText();
        } catch (Exception e) {
            return null;
        } finally {
            qr.reset();
        }
    }

    void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (cam != null) {
            cam.close();
            cam = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
