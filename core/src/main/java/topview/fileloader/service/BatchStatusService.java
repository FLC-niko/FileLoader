package topview.fileloader.service;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 批次状态与结果服务。
 */
public class BatchStatusService {
    private static final Logger logger = Logger.getLogger(BatchStatusService.class.getName());

    public static class BatchStatusResult {
        private final int code;
        private final String msg;
        private final boolean downloadable;
        private final int successWithoutErrorFiles;
        private final int successWithErrorFiles;
        private final int failureFiles;
        private final int unprocessedFiles;
        private final int processingFiles;
        private final int totalFiles;

        public BatchStatusResult(int code, String msg, boolean downloadable,
                int successWithoutErrorFiles, int successWithErrorFiles, int failureFiles,
                int unprocessedFiles, int processingFiles,
                int totalFiles) {
            this.code = code;
            this.msg = msg;
            this.downloadable = downloadable;
            this.successWithoutErrorFiles = successWithoutErrorFiles;
            this.successWithErrorFiles = successWithErrorFiles;
            this.failureFiles = failureFiles;
            this.unprocessedFiles = unprocessedFiles;
            this.processingFiles = processingFiles;
            this.totalFiles = totalFiles;
        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }

        public boolean isDownloadable() {
            return downloadable;
        }

        public int getSuccessWithoutErrorFiles() {
            return successWithoutErrorFiles;
        }

        public int getSuccessWithErrorFiles() {
            return successWithErrorFiles;
        }

        public int getFailureFiles() {
            return failureFiles;
        }

        public int getProcessedFiles() {
            int total = 0;
            if (successWithoutErrorFiles > 0) {
                total += successWithoutErrorFiles;
            }
            if (successWithErrorFiles > 0) {
                total += successWithErrorFiles;
            }
            if (failureFiles > 0) {
                total += failureFiles;
            }
            if (total > 0) {
                return total;
            }
            if (totalFiles >= 0 && unprocessedFiles >= 0 && processingFiles >= 0) {
                return Math.max(0, totalFiles - unprocessedFiles - processingFiles);
            }
            return -1;
        }

        public int getUnprocessedFiles() {
            return unprocessedFiles;
        }

        public int getProcessingFiles() {
            return processingFiles;
        }

        public int getTotalFiles() {
            return totalFiles;
        }
    }

    public static BatchStatusResult fetchBatchStatus(String batchId) {
        try (Response response = DesktopApiClient.execute(
                DesktopApiClient.requestBuilder(batchUrl(batchId, "batchStatus"), true).get().build())) {
            int httpCode = response.code();
            String body = DesktopApiClient.readResponseBody(response);
            logger.info("batchStatus response: " + body);
            DesktopApiClient.clearAuthIfUnauthorized(httpCode, body);

            JsonObject root = DesktopApiClient.parseObject(body);
            int bizCode = DesktopApiClient.getInt(root, "code", httpCode);
            String msg = DesktopApiClient.responseMessage(root, httpCode, "状态未知");
            if (httpCode != 200 || bizCode != 200) {
                return new BatchStatusResult(bizCode, msg, false, -1, -1, -1, -1, -1, -1);
            }

            JsonObject data = DesktopApiClient.getObject(root, "data");
            int successWithoutErrorFiles = DesktopApiClient.getInt(data, "successWithoutErrorFiles", -1);
            int successWithErrorFiles = DesktopApiClient.getInt(data, "successWithErrorFiles", -1);
            int failureFiles = DesktopApiClient.getInt(data, "failureFiles", -1);
            int unprocessedFiles = DesktopApiClient.getInt(data, "unprocessedFiles", -1);
            int processingFiles = DesktopApiClient.getInt(data, "processingFiles", -1);
            int totalFiles = DesktopApiClient.getInt(data, "totalFiles", -1);
            boolean successFlag = DesktopApiClient.getBoolean(root, "success", bizCode == 200);
            boolean downloadable = resolveDownloadable(bizCode, successFlag, msg, unprocessedFiles, processingFiles,
                    totalFiles);
            return new BatchStatusResult(bizCode, msg.isBlank() ? "查询成功" : msg, downloadable,
                    successWithoutErrorFiles, successWithErrorFiles, failureFiles,
                    unprocessedFiles, processingFiles, totalFiles);
        } catch (IOException e) {
            logger.log(Level.WARNING, "batchStatus error", e);
            return new BatchStatusResult(-1, DesktopApiClient.friendlyNetworkMessage(e),
                    false, -1, -1, -1, -1, -1, -1);
        } catch (Exception e) {
            logger.log(Level.WARNING, "batchStatus response error", e);
            return new BatchStatusResult(-1, "服务器响应无法识别", false, -1, -1, -1, -1, -1, -1);
        }
    }

    public static String downloadBatchResult(String batchId, File saveDir) {
        return downloadFile(batchUrl(batchId, "batchResult"), saveDir,
                "batch_" + batchId + "_result.xlsx", "没有可下载的文件（汇总报告不存在）");
    }

    private static String downloadFile(HttpUrl url, File saveDir, String defaultName, String notFoundMessage) {
        Request request = DesktopApiClient.requestBuilder(url, true).header("Accept", "*/*").get().build();
        try (Response response = DesktopApiClient.execute(request)) {
            int responseCode = response.code();
            if (responseCode == 200) {
                File saveFile = DownloadFiles.save(response, saveDir, defaultName);
                logger.info("Downloaded successfully: " + saveFile.getAbsolutePath());
                return "下载成功: " + saveFile.getAbsolutePath();
            }

            String errorBody = DesktopApiClient.readResponseBody(response);
            DesktopApiClient.clearAuthIfUnauthorized(responseCode, errorBody);
            String errorMessage = errorBody.isBlank() ? "" : errorBody;
            if (responseCode == 404 && errorMessage.isBlank()) {
                return notFoundMessage;
            }
            if (!errorMessage.isBlank()) {
                return errorMessage;
            }
            return "下载失败，服务器响应码: " + responseCode;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "batchResult download error", e);
            return "下载异常: " + DesktopApiClient.friendlyNetworkMessage(e);
        }
    }

    private static boolean resolveDownloadable(int bizCode, boolean successFlag, String msg,
            int unprocessedFiles, int processingFiles, int totalFiles) {
        boolean apiSuccess = (bizCode == 200) || successFlag;
        if (unprocessedFiles >= 0 && processingFiles >= 0 && totalFiles >= 0) {
            return apiSuccess && unprocessedFiles == 0 && processingFiles == 0 && totalFiles > 0;
        }
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        boolean msgAllows = msg.contains("完成") || msg.contains("可下载")
                || lower.contains("finished") || lower.contains("done")
                || lower.contains("ready") || lower.contains("download");
        return apiSuccess && msgAllows;
    }

    private static HttpUrl batchUrl(String batchId, String action) {
        return DesktopApiClient.urlBuilder("batch")
                .addPathSegment(batchId == null ? "" : batchId.trim())
                .addPathSegment(action)
                .build();
    }

}
