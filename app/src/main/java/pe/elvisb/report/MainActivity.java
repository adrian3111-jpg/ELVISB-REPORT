package pe.elvisb.report;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.webkit.JsPromptResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends Activity {
    private static final int CAMERA_REQ = 41;
    private static final int FILE_REQ = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        WebView.enableSlowWholeDocumentDraw();
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new ElvisbChromeClient());

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
        }

        try {
            String html = readAsset("index.html");
            html = html.replace("</head>", "<script>window.__ELVISB_ANDROID__=true;</script></head>");
            web.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo iniciar ELVISB Report: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) out.append(line).append('\n');
        br.close();
        return out.toString();
    }

    private void openImageChooser(ValueCallback<Uri[]> callback) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, FILE_REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQ && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    private String safePdfName(String raw) {
        String n = raw == null ? "ELVISB_Report" : raw.trim();
        if (n.toLowerCase().endsWith(".pdf")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ _.-]", "_");
        if (n.isEmpty()) n = "ELVISB_Report";
        if (n.length() > 90) n = n.substring(0, 90);
        return n;
    }

    private File pdfFile(String rawName) {
        File dir = new File(getCacheDir(), "reports");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, safePdfName(rawName) + ".pdf");
    }

    private void generatePdf(String requestedName) {
        final String safeName = safePdfName(requestedName);
        final File outFile = pdfFile(safeName);

        try {
            final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    outFile,
                    ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE |
                            ParcelFileDescriptor.MODE_READ_WRITE
            );

            final PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(safeName);

            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new PrintAttributes.Resolution("elvisb", "ELVISB", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            CancellationSignal signal = new CancellationSignal();

            adapter.onLayout(
                    null,
                    attrs,
                    signal,
                    new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override
                        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                            adapter.onWrite(
                                    new PageRange[]{PageRange.ALL_PAGES},
                                    pfd,
                                    new CancellationSignal(),
                                    new PrintDocumentAdapter.WriteResultCallback() {
                                        @Override
                                        public void onWriteFinished(PageRange[] pages) {
                                            try { pfd.close(); } catch (Exception ignored) {}
                                            adapter.onFinish();
                                            runOnUiThread(() -> web.evaluateJavascript(
                                                    "window.onNativePdfReady && window.onNativePdfReady(" +
                                                            jsQuote(safeName + ".pdf") + ");",
                                                    null
                                            ));
                                        }

                                        @Override
                                        public void onWriteFailed(CharSequence error) {
                                            try { pfd.close(); } catch (Exception ignored) {}
                                            adapter.onFinish();
                                            String msg = error == null ? "Error al generar PDF" : error.toString();
                                            runOnUiThread(() -> {
                                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                                                web.evaluateJavascript(
                                                        "window.onNativePdfError && window.onNativePdfError(" +
                                                                jsQuote(msg) + ");",
                                                        null
                                                );
                                            });
                                        }
                                    }
                            );
                        }

                        @Override
                        public void onLayoutFailed(CharSequence error) {
                            try { pfd.close(); } catch (Exception ignored) {}
                            adapter.onFinish();
                            String msg = error == null ? "No se pudo preparar el PDF" : error.toString();
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            web.evaluateJavascript(
                                    "window.onNativePdfError && window.onNativePdfError(" + jsQuote(msg) + ");",
                                    null
                            );
                        }
                    },
                    null
            );
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo generar el PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
            web.evaluateJavascript(
                    "window.onNativePdfError && window.onNativePdfError(" + jsQuote(e.getMessage()) + ");",
                    null
            );
        }
    }

    private String jsQuote(String value) {
        if (value == null) return "null";
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "") + "'";
    }

    private Uri pdfUri(File file) {
        return FileProvider.getUriForFile(
                this,
                getPackageName() + ".provider",
                file
        );
    }

    private void showPdf(String requestedName) {
        File file = pdfFile(requestedName);
        if (!file.exists() || file.length() == 0) {
            Toast.makeText(this, "Primero genera o actualiza el PDF.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = pdfUri(file);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay un visor de PDF instalado.", Toast.LENGTH_LONG).show();
        }
    }

    private void sharePdf(String requestedName) {
        File file = pdfFile(requestedName);
        if (!file.exists() || file.length() == 0) {
            Toast.makeText(this, "Primero genera o actualiza el PDF.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = pdfUri(file);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_SUBJECT, file.getName());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir informe"));
    }

    private class ElvisbChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
                    request.deny();
                    Toast.makeText(MainActivity.this,
                            "Concede permiso de cámara y vuelve a pulsar Cámara.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    request.grant(request.getResources());
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            openImageChooser(filePathCallback);
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message,
                                  String defaultValue, JsPromptResult result) {
            if ("__ELVISB_GENERATE__".equals(message)) {
                result.confirm("");
                generatePdf(defaultValue);
                return true;
            }
            if ("__ELVISB_SHOW__".equals(message)) {
                result.confirm("");
                showPdf(defaultValue);
                return true;
            }
            if ("__ELVISB_SHARE__".equals(message)) {
                result.confirm("");
                sharePdf(defaultValue);
                return true;
            }
            return super.onJsPrompt(view, url, message, defaultValue, result);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript(
                    "(window.elvisbBack ? window.elvisbBack() : false)",
                    value -> {
                        if (!"true".equals(value)) {
                            if (web.canGoBack()) web.goBack();
                            else MainActivity.super.onBackPressed();
                        }
                    }
            );
        } else {
            super.onBackPressed();
        }
    }
}
