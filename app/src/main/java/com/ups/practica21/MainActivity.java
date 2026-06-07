package com.ups.practica21;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.widget.*;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity — Práctica 2-1, Parte 1
 * =====================================
 * Controla la cámara, muestra el stream procesado y envía parámetros
 * al código nativo C++ (JNI) para los efectos de visión por computador.
 *
 * Modos de operación (botones):
 *   [Struss]     — Efecto Karl Struss: alpha blending de canales R y B
 *   [ChromaKey]  — Segmentación de fondo verde/azul + inserción de fondo sólido
 *   [Ruido+Filtro] — Añade ruido y aplica filtro espacial para atenuarlo
 *
 * Autor  : Michael Lata
 * Materia: Visión por Computador — Práctica 2-1
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Practica21";
    private static final int    CAMERA_PERMISSION_CODE = 100;

    // Carga la librería nativa compilada con CMake/NDK
    static { System.loadLibrary("practica21"); }

    // --- UI ---
    private ImageView        previewView;
    private SeekBar          sliderAlpha;
    private SeekBar          sliderVarianza;
    private SeekBar          sliderKernel;
    private TextView         tvAlpha, tvVarianza, tvKernel, tvFps, tvModo;
    private RadioGroup       rgModo;
    private RadioGroup       rgFiltro;
    private RadioGroup       rgRuido;

    // --- Estado ---
    private enum Modo { STRUSS, CHROMA_KEY, RUIDO_FILTRO }
    private Modo modoActual = Modo.STRUSS;

    private float   alpha     = 0.5f;
    private float   varianza  = 30f;
    private int     kernelSz  = 5;
    private int     tipoFiltro = 0;   // 0=Mediana, 1=Gaussiano
    private int     tipoRuido  = 0;   // 0=Gaussiano, 1=Speckle
    private int     hMin = 35, hMax = 85;  // Verde por defecto

    // --- Cámara ---
    private ExecutorService cameraExecutor;
    private long            lastFrameTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Solicitar permiso de cámara en tiempo de ejecución (Android 6+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE);
        }
    }

    private void bindViews() {
        previewView    = findViewById(R.id.imageView);
        sliderAlpha    = findViewById(R.id.sliderAlpha);
        sliderVarianza = findViewById(R.id.sliderVarianza);
        sliderKernel   = findViewById(R.id.sliderKernel);
        tvAlpha        = findViewById(R.id.tvAlpha);
        tvVarianza     = findViewById(R.id.tvVarianza);
        tvKernel       = findViewById(R.id.tvKernel);
        tvFps          = findViewById(R.id.tvFps);
        tvModo         = findViewById(R.id.tvModo);
        rgModo         = findViewById(R.id.rgModo);
        rgFiltro       = findViewById(R.id.rgFiltro);
        rgRuido        = findViewById(R.id.rgRuido);
    }

    private void setupListeners() {
        // Slider alpha (Efecto Struss: 0% Azul → 100% Rojo)
        sliderAlpha.setMax(100);
        sliderAlpha.setProgress(50);
        sliderAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                alpha = p / 100.0f;
                tvAlpha.setText(String.format("α = %.2f", alpha));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Slider varianza de ruido
        sliderVarianza.setMax(100);
        sliderVarianza.setProgress(30);
        sliderVarianza.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                varianza = p;
                tvVarianza.setText("Ruido σ² = " + p);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Slider tamaño de kernel (3..21, solo impares)
        sliderKernel.setMax(9);
        sliderKernel.setProgress(1);
        sliderKernel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                kernelSz = 3 + p * 2;   // 3,5,7,...,21
                tvKernel.setText("Kernel = " + kernelSz + "×" + kernelSz);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Selector de modo
        rgModo.setOnCheckedChangeListener((group, checkedId) -> {
            if      (checkedId == R.id.rbStruss)    modoActual = Modo.STRUSS;
            else if (checkedId == R.id.rbChromaKey) modoActual = Modo.CHROMA_KEY;
            else                                     modoActual = Modo.RUIDO_FILTRO;
            tvModo.setText("Modo: " + modoActual.name());
        });

        // Selector de filtro
        rgFiltro.setOnCheckedChangeListener((g, id) ->
                tipoFiltro = (id == R.id.rbMediana) ? 0 : 1);

        // Selector de ruido
        rgRuido.setOnCheckedChangeListener((g, id) ->
                tipoRuido = (id == R.id.rbGaussiano) ? 0 : 1);
    }

    // ---------------------------------------------------------------------------
    // Cámara con CameraX
    // ---------------------------------------------------------------------------
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                provider.unbindAll();
                provider.bindToLifecycle(this,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis);

            } catch (Exception e) {
                Log.e(TAG, "Error al iniciar cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Procesa cada frame de la cámara en el hilo del executor.
     * Convierte YUV → Bitmap → llama a JNI → muestra resultado en UI.
     */
    private void processFrame(ImageProxy image) {
        Bitmap raw = imageProxyToBitmap(image);
        int rotation = image.getImageInfo().getRotationDegrees();
        image.close();

        if (raw == null) return;

        // Rotar el bitmap según la orientación real del sensor
        Bitmap bmp;
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            bmp = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true)
                        .copy(Bitmap.Config.ARGB_8888, true);
            raw.recycle();
        } else {
            bmp = raw;
        }

        // Aplicar efecto según modo seleccionado
        switch (modoActual) {
            case STRUSS:
                nativeKarlStruss(bmp, alpha);
                break;
            case CHROMA_KEY:
                nativeChromaKey(bmp, hMin, hMax);
                break;
            case RUIDO_FILTRO:
                nativeAddNoise(bmp, hMin, hMax, varianza, tipoRuido);
                nativeSpatialFilter(bmp, tipoFiltro, kernelSz);
                break;
        }

        // Calcular FPS
        long now = System.currentTimeMillis();
        double fps = (lastFrameTime > 0) ? 1000.0 / (now - lastFrameTime) : 0;
        lastFrameTime = now;

        // Actualizar UI en hilo principal
        final Bitmap result = bmp;
        final String fpsStr = String.format("FPS: %.1f", fps);
        runOnUiThread(() -> {
            previewView.setImageBitmap(result);
            tvFps.setText(fpsStr);
        });
    }

    /**
     * Convierte ImageProxy (formato YUV_420_888 de CameraX) a Bitmap ARGB_8888.
     */
    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer  = planes[0].getBuffer();
        ByteBuffer uvBuffer = planes[2].getBuffer();

        byte[] yBytes  = new byte[yBuffer.remaining()];
        byte[] uvBytes = new byte[uvBuffer.remaining()];
        yBuffer.get(yBytes);
        uvBuffer.get(uvBytes);

        YuvImage yuv = new YuvImage(
                mergYUV(yBytes, uvBytes, image.getWidth(), image.getHeight()),
                ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
        byte[] jpg = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length)
                             .copy(Bitmap.Config.ARGB_8888, true);
    }

    private byte[] mergYUV(byte[] y, byte[] uv, int w, int h) {
        byte[] nv21 = new byte[y.length + uv.length];
        System.arraycopy(y,  0, nv21, 0,        y.length);
        System.arraycopy(uv, 0, nv21, y.length, uv.length);
        return nv21;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    // ---------------------------------------------------------------------------
    // Declaraciones nativas JNI (implementadas en native-lib.cpp)
    // ---------------------------------------------------------------------------
    public native void nativeKarlStruss(Bitmap bitmap, float alpha);
    public native void nativeChromaKey(Bitmap bitmap, int hMin, int hMax);
    public native void nativeAddNoise(Bitmap bitmap, int hMin, int hMax, float varianza, int tipo);
    public native void nativeSpatialFilter(Bitmap bitmap, int tipoFiltro, int kernelSize);
}
