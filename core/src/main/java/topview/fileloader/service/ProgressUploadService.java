package topview.fileloader.service;

import com.google.gson.JsonObject;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import topview.fileloader.model.UploadResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Streams one file to the desktop batch upload endpoint. */
public class ProgressUploadService {
    private static final Logger logger = Logger.getLogger(ProgressUploadService.class.getName());
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

    public static UploadResponse uploadFile(File file, String batchId, Consumer<Integer> progressCallback) {
        if (batchId == null || batchId.isBlank()) return errorResponse(400, "batchId 不能为空");
        if (file == null || !file.isFile()) return errorResponse(400, "文件不存在");
        Consumer<Integer> progress = progressCallback == null ? ignored -> { } : progressCallback;

        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            AtomicBoolean bodyStarted = new AtomicBoolean(false);
            try {
                HttpUrl url = DesktopApiClient.urlBuilder("batch/upload")
                        .addQueryParameter("batchId", batchId.trim())
                        .build();
                RequestBody fileBody = new ProgressFileBody(file, progress, bodyStarted);
                RequestBody multipart = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(), fileBody)
                        .addFormDataPart("fileName", file.getName())
                        .build();
                Request request = DesktopApiClient.requestBuilder(url, true).post(multipart).build();

                logger.info("开始上传文件: " + file.getName() + " (尝试 " + attempt + "/" + MAX_CONNECT_ATTEMPTS + ")");
                try (Response response = DesktopApiClient.execute(request)) {
                    int httpCode = response.code();
                    String responseText = DesktopApiClient.readResponseBody(response);
                    DesktopApiClient.clearAuthIfUnauthorized(httpCode, responseText);
                    UploadResponse upload = parseUploadResponse(httpCode, responseText);
                    if (httpCode == 200 && upload.getCode() == 200) progress.accept(100);
                    return upload;
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "上传网络异常: " + file.getName(), e);
                // Once bytes may have reached the server, an automatic retry could create a duplicate file.
                if (bodyStarted.get() || attempt == MAX_CONNECT_ATTEMPTS) {
                    String message = bodyStarted.get()
                            ? "上传结果未确认，请先检查任务状态后再重试"
                            : "无法连接服务器: " + safeMessage(e);
                    return errorResponse(500, message);
                }
                if (!sleepBeforeRetry()) return errorResponse(500, "上传中断");
            } catch (Exception e) {
                logger.log(Level.WARNING, "上传异常: " + file.getName(), e);
                return errorResponse(500, "上传失败: " + safeMessage(e));
            }
        }
        return errorResponse(500, "上传失败");
    }

    private static final class ProgressFileBody extends RequestBody {
        private final File file;
        private final Consumer<Integer> callback;
        private final AtomicBoolean started;

        ProgressFileBody(File file, Consumer<Integer> callback, AtomicBoolean started) {
            this.file = file;
            this.callback = callback;
            this.started = started;
        }

        @Override
        public MediaType contentType() {
            return OCTET_STREAM;
        }

        @Override
        public long contentLength() {
            return file.length();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            started.set(true);
            long sent = 0;
            long total = Math.max(1, file.length());
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    sink.write(buffer, 0, count);
                    sent += count;
                    callback.accept(Math.min(99, (int) (sent * 100 / total)));
                }
            }
        }
    }

    private static UploadResponse parseUploadResponse(int httpCode, String responseText) {
        if (responseText == null || responseText.isBlank()) return errorResponse(httpCode, "HTTP " + httpCode);
        try {
            JsonObject root = DesktopApiClient.parseObject(responseText);
            int code = DesktopApiClient.getInt(root, "code", httpCode);
            String msg = DesktopApiClient.responseMessage(root, httpCode, "");
            UploadResponse response = new UploadResponse();
            response.setCode(code);
            response.setMessage(msg.isBlank() ? "HTTP " + httpCode : msg);
            response.setSuccess(DesktopApiClient.getBoolean(root, "success", code == 200));
            JsonObject data = DesktopApiClient.getObject(root, "data");
            if (!data.entrySet().isEmpty()) {
                UploadResponse.UploadData uploadData = new UploadResponse.UploadData();
                uploadData.setFileId(DesktopApiClient.getString(data, "fileId"));
                uploadData.setBatchId(DesktopApiClient.getString(data, "batchId"));
                uploadData.setUploadTime(DesktopApiClient.getString(data, "uploadTime"));
                response.setData(uploadData);
            }
            return response;
        } catch (Exception e) {
            return errorResponse(httpCode, "服务器响应无法识别");
        }
    }

    private static boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static UploadResponse errorResponse(int code, String message) {
        UploadResponse response = new UploadResponse();
        response.setCode(code);
        response.setMessage(message);
        response.setSuccess(false);
        return response;
    }
}
