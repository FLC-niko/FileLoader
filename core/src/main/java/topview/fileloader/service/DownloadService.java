package topview.fileloader.service;

import java.io.File;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DownloadService {
    private static final Logger logger = Logger.getLogger(DownloadService.class.getName());

    public static String downloadBatch(String batchId, File saveDir, boolean withWrong) {
        String safeBatchId = batchId == null ? "" : batchId.trim();
        if (safeBatchId.isEmpty()) {
            logger.warning("Download failed: batchId is empty");
            return "下载失败: 批次ID为空";
        }

        HttpUrl url = DesktopApiClient.urlBuilder("batch")
                .addPathSegment(safeBatchId)
                .addPathSegment(withWrong ? "batchDownloadWithWrong" : "batchDownload")
                .build();
        String defaultName = "batch_" + safeBatchId + (withWrong ? "_wrong" : "") + ".zip";

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
            if (!errorBody.isBlank()) {
                logger.warning("Download error body: " + errorBody);
                return errorBody;
            }
            if (responseCode == 404) {
                return "没有可下载的文件（异常文件不存在）";
            }
            return "下载失败，服务器响应码: " + responseCode;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Download error", e);
            return "下载异常: " + DesktopApiClient.friendlyNetworkMessage(e);
        }
    }
}
