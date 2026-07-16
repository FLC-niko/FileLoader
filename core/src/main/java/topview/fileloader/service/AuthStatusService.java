package topview.fileloader.service;

import com.google.gson.JsonObject;
import topview.fileloader.config.AppConfig;

import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 桌面版 Token 认证服务。
 */
public class AuthStatusService {
    private static final Logger logger = Logger.getLogger(AuthStatusService.class.getName());

    public enum State {
        LOGGED_IN,
        UNAUTHORIZED,
        NETWORK_ERROR,
        SERVER_ERROR,
        PARSE_ERROR
    }

    public enum LogoutState {
        SUCCESS,
        UNAUTHORIZED,
        NETWORK_ERROR,
        SERVER_ERROR
    }

    public static class LoginUser {
        private final boolean loggedIn;
        private final String userId;
        private final String name;
        private final int role;
        private final String token;
        private final String loginTime;

        public LoginUser(boolean loggedIn, String userId, String name, int role, String token, String loginTime) {
            this.loggedIn = loggedIn;
            this.userId = userId;
            this.name = name;
            this.role = role;
            this.token = token;
            this.loginTime = loginTime;
        }

        public boolean isLoggedIn() {
            return loggedIn;
        }

        public String getUserId() {
            return userId;
        }

        public String getName() {
            return name;
        }

        public int getRole() {
            return role;
        }

        public String getToken() {
            return token;
        }

        public String getLoginTime() {
            return loginTime;
        }
    }

    public static class LoginStatusResult {
        private final State state;
        private final int httpCode;
        private final int bizCode;
        private final String message;
        private final LoginUser user;

        public LoginStatusResult(State state, int httpCode, int bizCode, String message, LoginUser user) {
            this.state = state;
            this.httpCode = httpCode;
            this.bizCode = bizCode;
            this.message = message;
            this.user = user;
        }

        public State getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public int getBizCode() {
            return bizCode;
        }

        public String getMessage() {
            return message;
        }

        public LoginUser getUser() {
            return user;
        }
    }

    public static class LogoutResult {
        private final LogoutState state;
        private final int httpCode;
        private final String message;
        private final String redirectLocation;

        public LogoutResult(LogoutState state, int httpCode, String message, String redirectLocation) {
            this.state = state;
            this.httpCode = httpCode;
            this.message = message;
            this.redirectLocation = redirectLocation;
        }

        public LogoutState getState() {
            return state;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getMessage() {
            return message;
        }

        public String getRedirectLocation() {
            return redirectLocation;
        }
    }

    public static LoginStatusResult queryLoginStatus() {
        if (!AppConfig.hasAuthToken()) {
            return new LoginStatusResult(State.UNAUTHORIZED, 0, 401, "当前未登录", null);
        }
        BatchRecordsService.Result validation = BatchRecordsService.fetch();
        if (!validation.isAvailable()) {
            State state = switch (validation.getState()) {
                case UNAUTHORIZED -> State.UNAUTHORIZED;
                case NETWORK_ERROR -> State.NETWORK_ERROR;
                case PARSE_ERROR -> State.PARSE_ERROR;
                case SERVER_ERROR -> State.SERVER_ERROR;
                case AVAILABLE -> State.LOGGED_IN;
            };
            return new LoginStatusResult(
                    state,
                    validation.getHttpCode(),
                    validation.getBusinessCode(),
                    validation.getMessage(),
                    null);
        }
        LoginUser user = new LoginUser(
                true,
                AppConfig.getAuthUserId(),
                AppConfig.getAuthName(),
                AppConfig.getAuthRole(),
                AppConfig.getAuthToken(),
                AppConfig.getAuthLoginTime());
        return new LoginStatusResult(State.LOGGED_IN, validation.getHttpCode(), 200, "登录有效", user);
    }

    public static LoginStatusResult login(String userId, String password) {
        try {
            HttpUrl url = DesktopApiClient.urlBuilder("auth/login")
                    .addQueryParameter("userId", userId == null ? "" : userId.trim())
                    .addQueryParameter("password", password == null ? "" : password)
                    .build();
            Request request = DesktopApiClient.requestBuilder(url, false)
                    .post(RequestBody.create(new byte[0], null))
                    .build();
            try (Response response = DesktopApiClient.execute(request)) {
            int httpCode = response.code();
            String body = DesktopApiClient.readResponseBody(response);
            JsonObject root = DesktopApiClient.parseObject(body);
            int bizCode = DesktopApiClient.getInt(root, "code", httpCode);
            String msg = DesktopApiClient.responseMessage(root, httpCode, "");

            if (DesktopApiClient.isUnauthorized(httpCode, root)) {
                return new LoginStatusResult(State.UNAUTHORIZED, httpCode, bizCode, msg, null);
            }
            if (httpCode != 200 || bizCode != 200) {
                return new LoginStatusResult(State.SERVER_ERROR, httpCode, bizCode,
                        msg.isBlank() ? "登录失败" : msg, null);
            }

            JsonObject data = DesktopApiClient.getObject(root, "data");
            String token = DesktopApiClient.getString(data, "token");
            if (token.isBlank()) {
                return new LoginStatusResult(State.PARSE_ERROR, httpCode, bizCode, "响应中缺少 token", null);
            }

            LoginUser user = new LoginUser(
                    DesktopApiClient.getBoolean(data, "loggedIn", true),
                    DesktopApiClient.getString(data, "userId"),
                    DesktopApiClient.getString(data, "name"),
                    DesktopApiClient.getInt(data, "role", 0),
                    token,
                    DesktopApiClient.getString(data, "loginTime"));

            AppConfig.setAuthSession(user.getToken(), user.getUserId(), user.getName(), user.getRole(), user.getLoginTime());
            return new LoginStatusResult(State.LOGGED_IN, httpCode, bizCode,
                    msg.isBlank() ? "登录成功" : msg, user);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Desktop login request failed", e);
            return new LoginStatusResult(State.NETWORK_ERROR, -1, -1,
                    DesktopApiClient.friendlyNetworkMessage(e), null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Desktop login parse failed", e);
            return new LoginStatusResult(State.PARSE_ERROR, -1, -1, "响应解析异常: " + e.getMessage(), null);
        }
    }

    public static LogoutResult logout() {
        try {
            Request request = DesktopApiClient.requestBuilder("auth/logout", true)
                    .post(RequestBody.create(new byte[0], null))
                    .build();
            try (Response response = DesktopApiClient.execute(request)) {
            int httpCode = response.code();
            String body = DesktopApiClient.readResponseBody(response);
            JsonObject root = DesktopApiClient.parseObject(body);
            String msg = DesktopApiClient.responseMessage(root, httpCode, "");

            if (DesktopApiClient.isUnauthorized(httpCode, root)) {
                return new LogoutResult(LogoutState.UNAUTHORIZED, httpCode,
                        msg.isBlank() ? "Token 已失效，本地登录状态已清理" : msg, null);
            }
            if (httpCode != 200) {
                return new LogoutResult(LogoutState.SERVER_ERROR, httpCode,
                        msg.isBlank() ? "退出登录失败 (HTTP " + httpCode + ")" : msg, null);
            }
            return new LogoutResult(LogoutState.SUCCESS, httpCode,
                    msg.isBlank() ? "退出成功" : msg, null);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Desktop logout request failed", e);
            return new LogoutResult(LogoutState.NETWORK_ERROR, -1,
                    DesktopApiClient.friendlyNetworkMessage(e), null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Desktop logout parse failed", e);
            return new LogoutResult(LogoutState.SERVER_ERROR, -1, "退出登录异常: " + e.getMessage(), null);
        } finally {
            AppConfig.clearAuthSession();
        }
    }
}
