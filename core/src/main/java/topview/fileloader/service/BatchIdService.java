package topview.fileloader.service;

import com.google.gson.JsonObject;

import okhttp3.Request;
import okhttp3.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 批次ID服务类，调用桌面版 v2 API 获取 batchId。
 */
public class BatchIdService {
    private static final Logger logger = Logger.getLogger(BatchIdService.class.getName());

    public static class BatchIdResult {
        private final int code;
        private final String msg;
        private final String batchId;

        public BatchIdResult(int code, String msg, String batchId) {
            this.code = code;
            this.msg = msg;
            this.batchId = batchId;
        }

        public int getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }

        public String getBatchId() {
            return batchId;
        }
    }

    public static BatchIdResult fetchBatchId() {
        try (Response response = DesktopApiClient.execute(
                DesktopApiClient.requestBuilder("batch/getBatchId", true).get().build())) {
            int httpCode = response.code();
            String body = DesktopApiClient.readResponseBody(response);
            logger.info("getBatchId response: " + body);
            DesktopApiClient.clearAuthIfUnauthorized(httpCode, body);

            JsonObject root = DesktopApiClient.parseObject(body);
            int bizCode = DesktopApiClient.getInt(root, "code", httpCode);
            String msg = DesktopApiClient.responseMessage(root, httpCode, "获取批次ID失败");
            if (httpCode != 200 || bizCode != 200) {
                return new BatchIdResult(bizCode, msg, null);
            }

            JsonObject data = DesktopApiClient.getObject(root, "data");
            String batchId = DesktopApiClient.getString(data, "batchId");
            return new BatchIdResult(bizCode, msg.isBlank() ? "获取成功" : msg, batchId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "getBatchId error", e);
            return new BatchIdResult(-1, "获取批次ID异常: " + e.getMessage(), null);
        }
    }
}
