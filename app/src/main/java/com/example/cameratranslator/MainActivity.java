package com.example.cameratranslator;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final int      REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS    = {Manifest.permission.CAMERA};
    private static final long     SCAN_INTERVAL_MS         = 1500;

    // Languages not supported by ML Kit Translate — always use MyMemory for these
    private static final Set<String> MYMEMORY_ONLY =
            new HashSet<>(Arrays.asList("tl")); // Filipino/Tagalog

    // ── Views ──────────────────────────────────────────────────────────────
    private PreviewView     previewView;
    private TextOverlayView overlayView;
    private Spinner         sourceLanguageSpinner;
    private Spinner         targetLanguageSpinner;
    private TextView        detectedText;
    private TextView        translatedText;
    private TextView        targetLanguageLabel;
    private TextView        sourceLanguageLabel;
    private CardView        resultCard;
    private LinearLayout    processingOverlay;
    private TextView        processingLabel;
    private ImageButton     freezeButton;

    // ── Threading ─────────────────────────────────────────────────────────
    private ExecutorService       cameraExecutor;
    private final ExecutorService translationExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler         = new Handler(Looper.getMainLooper());

    // ── State ─────────────────────────────────────────────────────────────
    private final AtomicBoolean isTranslating = new AtomicBoolean(false);
    private final AtomicBoolean isFrozen      = new AtomicBoolean(false);
    private long lastScanTime = 0;

    private final Object        blocksLock     = new Object();
    private List<DetectedBlock> detectedBlocks = new ArrayList<>();

    // ── Language maps ─────────────────────────────────────────────────────
    private LinkedHashMap<String, String> languageMap;
    private LinkedHashMap<String, String> codeToScript;
    private String[] languageNames;

    // ══════════════════════════════════════════════════════════════════════
    // DetectedBlock model
    // ══════════════════════════════════════════════════════════════════════
    static class DetectedBlock {
        final String text;
        final RectF  bounds;

        DetectedBlock(String text, RectF bounds) {
            this.text   = text;
            this.bounds = bounds;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initLanguageMaps();
        setupSpinners();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // Tap on overlay → hit-test detected blocks
        overlayView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float tx = event.getX();
                float ty = event.getY();
                synchronized (blocksLock) {
                    for (DetectedBlock block : detectedBlocks) {
                        if (block.bounds.contains(tx, ty)) {
                            overlayView.setHighlighted(block.bounds);
                            initiateTranslation(block.text);
                            return true;
                        }
                    }
                }
            }
            return false;
        });

        // Freeze / unfreeze button
        freezeButton.setOnClickListener(v -> {
            if (isFrozen.get()) {
                isFrozen.set(false);
                freezeButton.setImageResource(R.drawable.ic_camera);
                freezeButton.setAlpha(1f);
                overlayView.clearHighlight();
                resultCard.setVisibility(View.GONE);
            } else {
                isFrozen.set(true);
                freezeButton.setImageResource(R.drawable.ic_camera_pause);
                freezeButton.setAlpha(0.7f);
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // View init
    // ══════════════════════════════════════════════════════════════════════
    private void initViews() {
        previewView           = findViewById(R.id.previewView);
        overlayView           = findViewById(R.id.overlayView);
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner);
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner);
        detectedText          = findViewById(R.id.detectedText);
        translatedText        = findViewById(R.id.translatedText);
        targetLanguageLabel   = findViewById(R.id.targetLanguageLabel);
        sourceLanguageLabel   = findViewById(R.id.sourceLanguageLabel);
        resultCard            = findViewById(R.id.resultCard);
        processingOverlay     = findViewById(R.id.processingOverlay);
        processingLabel       = findViewById(R.id.processingLabel);
        freezeButton          = findViewById(R.id.captureButton);

        View modelStatus = findViewById(R.id.modelStatusText);
        if (modelStatus != null) modelStatus.setVisibility(View.GONE);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Language maps
    // ══════════════════════════════════════════════════════════════════════
    private void initLanguageMaps() {
        languageMap = new LinkedHashMap<>();
        languageMap.put("Auto Detect", "auto");
        languageMap.put("Chinese",     "zh");
        languageMap.put("Dutch",       "nl");
        languageMap.put("English",     "en");
        languageMap.put("Filipino",    "tl");
        languageMap.put("French",      "fr");
        languageMap.put("German",      "de");
        languageMap.put("Hindi",       "hi");
        languageMap.put("Indonesian",  "id");
        languageMap.put("Italian",     "it");
        languageMap.put("Japanese",    "ja");
        languageMap.put("Korean",      "ko");
        languageMap.put("Portuguese",  "pt");
        languageMap.put("Spanish",     "es");
        languageMap.put("Swedish",     "sv");
        languageMap.put("Vietnamese",  "vi");

        codeToScript = new LinkedHashMap<>();
        codeToScript.put("zh", "chinese");
        codeToScript.put("ja", "japanese");
        codeToScript.put("ko", "korean");
        codeToScript.put("hi", "devanagari");

        languageNames = languageMap.keySet().toArray(new String[0]);
    }

    private void setupSpinners() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, languageNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceLanguageSpinner.setAdapter(adapter);
        targetLanguageSpinner.setAdapter(adapter);
        sourceLanguageSpinner.setSelection(positionOf("Auto Detect"));
        targetLanguageSpinner.setSelection(positionOf("English"));
    }

    private int positionOf(String name) {
        for (int i = 0; i < languageNames.length; i++) {
            if (languageNames[i].equals(name)) return i;
        }
        return 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Camera
    // ══════════════════════════════════════════════════════════════════════
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    long now = System.currentTimeMillis();
                    if (isFrozen.get()) { imageProxy.close(); return; }
                    if ((now - lastScanTime) < SCAN_INTERVAL_MS) { imageProxy.close(); return; }
                    lastScanTime = now;

                    Bitmap bmp      = imageProxyToBitmap(imageProxy);
                    int    rotation = imageProxy.getImageInfo().getRotationDegrees();
                    imageProxy.close();
                    if (bmp == null) return;

                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    Bitmap rotated = Bitmap.createBitmap(
                            bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                    bmp.recycle();

                    runOcrOnFrame(rotated);
                });

                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera init failed", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Continuous OCR — detect all text blocks in full frame
    // ══════════════════════════════════════════════════════════════════════
    private void runOcrOnFrame(Bitmap bitmap) {
        String sourceName = sourceLanguageSpinner.getSelectedItem().toString();
        String sourceLang = languageMap.getOrDefault(sourceName, "auto");

        if ("auto".equals(sourceLang)) {
            // Auto Detect — run all 4 recognizers in parallel so CJK scripts are found
            runMultiScriptOcr(bitmap);
        } else {
            // Known language — single recognizer only
            String script = codeToScript.getOrDefault(sourceLang, "latin");
            runSingleScriptOcr(bitmap, script);
        }
    }

    // Single recognizer for a known source language
    private void runSingleScriptOcr(Bitmap bitmap, String script) {
        TextRecognizer recognizer = buildRecognizer(script);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(visionText -> {
                    recognizer.close();
                    List<DetectedBlock> newBlocks = mergeAndMapBlocks(
                            visionText.getTextBlocks(),
                            bitmap.getWidth(), bitmap.getHeight());
                    bitmap.recycle();
                    synchronized (blocksLock) { detectedBlocks = newBlocks; }
                    mainHandler.post(() -> overlayView.setBlocks(newBlocks));
                })
                .addOnFailureListener(e -> {
                    recognizer.close();
                    bitmap.recycle();
                });
    }

    // Run Latin + Chinese + Japanese + Korean recognizers simultaneously,
    // then merge and deduplicate all detected blocks
    private void runMultiScriptOcr(Bitmap bitmap) {
        String[]             scripts   = {"latin", "chinese", "japanese", "korean"};
        List<Text.TextBlock> allBlocks = new ArrayList<>();
        int[]                remaining = {scripts.length};
        Object               mergeLock = new Object();

        for (String script : scripts) {
            TextRecognizer recognizer = buildRecognizer(script);
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(visionText -> {
                        recognizer.close();
                        synchronized (mergeLock) {
                            allBlocks.addAll(visionText.getTextBlocks());
                            remaining[0]--;
                            if (remaining[0] == 0) {
                                List<DetectedBlock> merged = mergeAndMapBlocks(
                                        allBlocks, bitmap.getWidth(), bitmap.getHeight());
                                bitmap.recycle();
                                synchronized (blocksLock) { detectedBlocks = merged; }
                                mainHandler.post(() -> overlayView.setBlocks(merged));
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        recognizer.close();
                        synchronized (mergeLock) {
                            remaining[0]--;
                            if (remaining[0] == 0) bitmap.recycle();
                        }
                    });
        }
    }

    // Map bounding boxes to overlay coords and deduplicate overlapping boxes
    private List<DetectedBlock> mergeAndMapBlocks(List<Text.TextBlock> rawBlocks,
                                                  int imgWidth, int imgHeight) {
        List<DetectedBlock> list  = new ArrayList<>();
        int                 viewW = overlayView.getWidth();
        int                 viewH = overlayView.getHeight();
        if (viewW == 0 || viewH == 0) return list;

        float       scaleX = (float) viewW / imgWidth;
        float       scaleY = (float) viewH / imgHeight;
        int         pad    = 8;
        List<RectF> seen   = new ArrayList<>();

        for (Text.TextBlock block : rawBlocks) {
            String blockText = block.getText().trim();
            if (blockText.isEmpty()) continue;
            Rect r = block.getBoundingBox();
            if (r == null) continue;

            RectF mapped = new RectF(
                    (r.left   - pad) * scaleX,
                    (r.top    - pad) * scaleY,
                    (r.right  + pad) * scaleX,
                    (r.bottom + pad) * scaleY);

            // Skip if >80% overlapping with an already-added box (dedup across recognizers)
            boolean duplicate = false;
            for (RectF existing : seen) {
                RectF inter = new RectF();
                if (inter.setIntersect(existing, mapped)) {
                    float interArea  = inter.width()  * inter.height();
                    float mappedArea = mapped.width() * mapped.height();
                    if (mappedArea > 0 && (interArea / mappedArea) > 0.8f) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) {
                seen.add(mapped);
                list.add(new DetectedBlock(blockText, mapped));
            }
        }
        return list;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Translation entry point (called on tap)
    // ══════════════════════════════════════════════════════════════════════
    private void initiateTranslation(String text) {
        if (isTranslating.getAndSet(true)) return;

        String sourceName = sourceLanguageSpinner.getSelectedItem().toString();
        String sourceLang = languageMap.getOrDefault(sourceName, "auto");

        mainHandler.post(() -> {
            detectedText.setText(text);
            showProcessing("Translating...");
            resultCard.setVisibility(View.GONE);
        });

        if ("auto".equals(sourceLang)) {
            autoDetectThenTranslate(text);
        } else {
            String targetName = targetLanguageSpinner.getSelectedItem().toString();
            String targetLang = languageMap.getOrDefault(targetName, "en");

            if (sourceLang.equals(targetLang)) {
                mainHandler.post(() -> {
                    translatedText.setText(text);
                    sourceLanguageLabel.setText(sourceName);
                    targetLanguageLabel.setText(targetName);
                    resultCard.setVisibility(View.VISIBLE);
                    hideProcessing();
                });
                isTranslating.set(false);
            } else {
                doTranslate(text, sourceLang, targetLang, sourceName, targetName);
            }
        }
    }

    private void autoDetectThenTranslate(String text) {
        LanguageIdentifier identifier = LanguageIdentification.getClient();
        identifier.identifyLanguage(text)
                .addOnSuccessListener(langCode -> {
                    identifier.close();
                    String detectedLang = "und".equals(langCode) ? "en" : langCode;
                    String detectedName = getNameFromCode(detectedLang);
                    String targetName   = targetLanguageSpinner.getSelectedItem().toString();
                    String targetLang   = languageMap.getOrDefault(targetName, "en");

                    mainHandler.post(() ->
                            sourceLanguageLabel.setText("Auto → " + detectedName));

                    if (detectedLang.equals(targetLang)) {
                        mainHandler.post(() -> {
                            translatedText.setText(text);
                            targetLanguageLabel.setText(targetName);
                            resultCard.setVisibility(View.VISIBLE);
                            hideProcessing();
                        });
                        isTranslating.set(false);
                    } else {
                        doTranslate(text, detectedLang, targetLang,
                                "Auto → " + detectedName, targetName);
                    }
                })
                .addOnFailureListener(e -> {
                    identifier.close();
                    mainHandler.post(this::hideProcessing);
                    isTranslating.set(false);
                });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Translation router
    //   Filipino (tl) involved  → MyMemory only (needs internet)
    //   Everything else         → ML Kit offline, MyMemory as fallback
    // ══════════════════════════════════════════════════════════════════════
    private void doTranslate(String text, String sourceLang, String targetLang,
                             String sourceName, String targetName) {

        boolean needsOnline = MYMEMORY_ONLY.contains(sourceLang)
                || MYMEMORY_ONLY.contains(targetLang);

        if (needsOnline) {
            mainHandler.post(() -> showProcessing("Translating (online)..."));
            doTranslateMyMemory(text, sourceLang, targetLang, sourceName, targetName);
        } else {
            doTranslateOffline(text, sourceLang, targetLang, sourceName, targetName);
        }
    }

    // ── ML Kit offline ────────────────────────────────────────────────────
    private void doTranslateOffline(String text, String sourceLang, String targetLang,
                                    String sourceName, String targetName) {

        String mlSource = toMlKitCode(sourceLang);
        String mlTarget = toMlKitCode(targetLang);

        // If ML Kit doesn't support the language pair, fall back to MyMemory
        if (mlSource == null || mlTarget == null) {
            Log.w("CameraTranslator", "ML Kit unsupported pair: " + sourceLang + "→" + targetLang);
            doTranslateMyMemory(text, sourceLang, targetLang, sourceName, targetName);
            return;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(mlSource)
                .setTargetLanguage(mlTarget)
                .build();

        Translator translator = Translation.getClient(options);
        mainHandler.post(() -> showProcessing("Preparing model..."));

        translator.downloadModelIfNeeded()
                .addOnSuccessListener(unused -> {
                    mainHandler.post(() -> showProcessing("Translating..."));
                    translator.translate(text)
                            .addOnSuccessListener(result -> {
                                translator.close();
                                mainHandler.post(() -> {
                                    translatedText.setText(result);
                                    sourceLanguageLabel.setText(sourceName);
                                    targetLanguageLabel.setText(targetName);
                                    resultCard.setVisibility(View.VISIBLE);
                                    hideProcessing();
                                });
                                isTranslating.set(false);
                            })
                            .addOnFailureListener(e -> {
                                translator.close();
                                Log.w("CameraTranslator", "ML Kit translate failed: " + e.getMessage());
                                // Translation error — fall back to MyMemory
                                doTranslateMyMemory(text, sourceLang, targetLang,
                                        sourceName, targetName);
                            });
                })
                .addOnFailureListener(e -> {
                    translator.close();
                    Log.w("CameraTranslator", "Model download failed: " + e.getMessage());
                    // No internet to download model — fall back to MyMemory
                    mainHandler.post(() -> showProcessing("Trying backup..."));
                    doTranslateMyMemory(text, sourceLang, targetLang, sourceName, targetName);
                });
    }

    // ── MyMemory online fallback (also handles Filipino) ──────────────────
    private void doTranslateMyMemory(String text, String sourceLang, String targetLang,
                                     String sourceName, String targetName) {

        String query  = text.length() > 500 ? text.substring(0, 500) : text;
        String urlStr = "https://api.mymemory.translated.net/get?q="
                + Uri.encode(query)
                + "&langpair=" + sourceLang + "|" + targetLang
                + "&of=json";

        translationExecutor.execute(() -> {
            try {
                URL               apiUrl = new URL(urlStr);
                HttpURLConnection conn   = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(7000);
                conn.setReadTimeout(7000);

                int status = conn.getResponseCode();
                if (status != 200) throw new Exception("HTTP " + status);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json       = new JSONObject(sb.toString());
                int        respStatus = json.optInt("responseStatus", 0);
                String     result;

                if (respStatus == 200) {
                    result = json.getJSONObject("responseData")
                            .getString("translatedText");
                    // ALL-CAPS result usually means an error string from MyMemory
                    if (result.equals(result.toUpperCase()) && result.length() < 80
                            && !sourceLang.startsWith("zh")
                            && !sourceLang.equals("ja")
                            && !sourceLang.equals("ko")) {
                        result = getBestMatch(json, result);
                    }
                } else {
                    result = getBestMatch(json, query);
                }

                final String finalResult = result;
                mainHandler.post(() -> {
                    translatedText.setText(finalResult);
                    sourceLanguageLabel.setText(sourceName);
                    targetLanguageLabel.setText(targetName);
                    resultCard.setVisibility(View.VISIBLE);
                    hideProcessing();
                });

            } catch (Exception e) {
                Log.e("CameraTranslator", "MyMemory error: " + e.getMessage());
                mainHandler.post(() -> {
                    hideProcessing();
                    Toast.makeText(this,
                            "Translation failed. Check your connection.",
                            Toast.LENGTH_SHORT).show();
                });
            }
            isTranslating.set(false);
        });
    }

    /** Pick the highest-quality translation from MyMemory's matches array */
    private String getBestMatch(JSONObject json, String fallback) {
        try {
            org.json.JSONArray matches = json.optJSONArray("matches");
            if (matches == null) return fallback;
            String best      = fallback;
            double bestScore = 0;
            for (int i = 0; i < matches.length(); i++) {
                JSONObject m     = matches.getJSONObject(i);
                double     score = m.optDouble("quality", 0);
                String     trans = m.optString("translation", "");
                if (score > bestScore && !trans.isEmpty()) {
                    bestScore = score;
                    best      = trans;
                }
            }
            return best;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ML Kit language code mapping — returns null if unsupported
    // ══════════════════════════════════════════════════════════════════════
    // Only maps languages actually in the app's spinner
    // tl (Filipino) is intentionally absent — always routed to MyMemory
    private String toMlKitCode(String code) {
        switch (code) {
            case "zh": return TranslateLanguage.CHINESE;
            case "nl": return TranslateLanguage.DUTCH;
            case "en": return TranslateLanguage.ENGLISH;
            case "fr": return TranslateLanguage.FRENCH;
            case "de": return TranslateLanguage.GERMAN;
            case "hi": return TranslateLanguage.HINDI;
            case "id": return TranslateLanguage.INDONESIAN;
            case "it": return TranslateLanguage.ITALIAN;
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            case "pt": return TranslateLanguage.PORTUGUESE;
            case "es": return TranslateLanguage.SPANISH;
            case "sv": return TranslateLanguage.SWEDISH;
            case "vi": return TranslateLanguage.VIETNAMESE;
            default:   return null; // unknown or tl → falls back to MyMemory
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════
    private TextRecognizer buildRecognizer(String script) {
        switch (script) {
            case "chinese":    return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case "japanese":   return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case "korean":     return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case "devanagari": return TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            default:           return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        try {
            ImageProxy.PlaneProxy[] planes  = imageProxy.getPlanes();
            ByteBuffer              yBuffer = planes[0].getBuffer();
            ByteBuffer              uBuffer = planes[1].getBuffer();
            ByteBuffer              vBuffer = planes[2].getBuffer();

            int    ySize = yBuffer.remaining();
            int    uSize = uBuffer.remaining();
            int    vSize = vBuffer.remaining();
            byte[] nv21  = new byte[ySize + uSize + vSize];

            yBuffer.get(nv21, 0,            ySize);
            vBuffer.get(nv21, ySize,         vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                    imageProxy.getWidth(), imageProxy.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()),
                    85, out);
            byte[] bytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private String getNameFromCode(String code) {
        for (java.util.Map.Entry<String, String> entry : languageMap.entrySet()) {
            if (entry.getValue().equals(code)) return entry.getKey();
        }
        return code.toUpperCase();
    }

    private void showProcessing(String message) {
        processingLabel.setText(message);
        processingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideProcessing() {
        processingOverlay.setVisibility(View.GONE);
    }

    private boolean allPermissionsGranted() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera();
            else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        translationExecutor.shutdown();
    }
}