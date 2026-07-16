package topview.fileloader.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import okhttp3.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Read-only desktop API probe used to validate connectivity and the persisted token.
 */
public final class BatchRecordsService {
    private static final Logger logger = Logger.getLogger(BatchRecordsService.class.getName());

    public enum State {
        AVAILABLE,
        UNAUTHORIZED,
        NETWORK_ERROR,
        SERVER_ERROR,
        PARSE_ERROR
    }

    public static final class Result {
        private final State state;
        private final int httpCode;
        private final int businessCode;
        private final String message;
        private final List<String> batchIds;

        Result(State state, int httpCode, int businessCode, String message, List<String> batchIds) {
            this.state = state;
            this.httpCode = httpCode;
            this.businessCode = businessCode;
            this.message = message == null ? "" : message;
            this.batchIds = Collections.unmodifiableList(new ArrayList<>(batchIds));
        }

        public State getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public int getBusinessCode() {
            return businessCode;
        }

        public String getMessage() {
            return message;
        }

        public List<String> getBatchIds() {
            return batchIds;
        }

        public boolean isAvailable() {
            return state == State.AVAILABLE;
        }
    }

    private BatchRecordsService() {
    }

    public static Result fetch() {
        try (Response response = DesktopApiClient.execute(
                DesktopApiClient.requestBuilder("batch/batchRecords", true).get().build())) {
            int httpCode = response.code();
            String body = DesktopApiClient.readResponseBody(response);
            JsonObject root = DesktopApiClient.parseObject(body);
            int businessCode = DesktopApiClient.getInt(root, "code", httpCode);
            String message = DesktopApiClient.responseMessage(root, httpCode, "连接检查失败");

            if (DesktopApiClient.isUnauthorized(httpCode, root)) {
                DesktopApiClient.clearAuthIfUnauthorized(httpCode, body);
                return new Result(State.UNAUTHORIZED, httpCode, businessCode,
                        message.isBlank() ? "登录已失效" : message, List.of());
            }
            if (httpCode != 200 || businessCode != 200) {
                return new Result(State.SERVER_ERROR, httpCode, businessCode, message, List.of());
            }

            JsonObject data = DesktopApiClient.getObject(root, "data");
            JsonElement batchIdsElement = data.get("batchIds");
            if (batchIdsElement == null || !batchIdsElement.isJsonArray()) {
                return new Result(State.PARSE_ERROR, httpCode, businessCode,
                        "服务器响应缺少批次列表", List.of());
            }

            List<String> batchIds = new ArrayList<>();
            JsonArray array = batchIdsElement.getAsJsonArray();
            for (JsonElement element : array) {
                if (element != null && !element.isJsonNull()) {
                    batchIds.add(element.getAsString());
                }
            }
            return new Result(State.AVAILABLE, httpCode, businessCode, "连接正常", batchIds);
        } catch (java.io.IOException e) {
            logger.log(Level.WARNING, "Batch records connection failed", e);
            return new Result(State.NETWORK_ERROR, -1, -1,
                    DesktopApiClient.friendlyNetworkMessage(e), List.of());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Batch records response could not be parsed", e);
            return new Result(State.PARSE_ERROR, -1, -1,
                    e.getMessage() == null ? "服务器响应无法识别" : e.getMessage(), List.of());
        }
    }
}
