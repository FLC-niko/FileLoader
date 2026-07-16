package topview.fileloader.service;

import topview.fileloader.config.AppConfig;
import topview.fileloader.config.AppPaths;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** A small, read-only support check for non-technical users. */
public final class SelfCheckService {
    public enum Status {
        OK,
        WARNING,
        ERROR
    }

    public static final class CheckItem {
        private final Status status;
        private final String title;
        private final String message;
        private final String technicalDetail;

        CheckItem(Status status, String title, String message, String technicalDetail) {
            this.status = status;
            this.title = title;
            this.message = message;
            this.technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }

        public Status getStatus() {
            return status;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public String getTechnicalDetail() {
            return technicalDetail;
        }
    }

    public static final class Result {
        private final CheckItem server;
        private final CheckItem login;
        private final CheckItem storage;

        Result(CheckItem server, CheckItem login, CheckItem storage) {
            this.server = server;
            this.login = login;
            this.storage = storage;
        }

        public CheckItem getServer() {
            return server;
        }

        public CheckItem getLogin() {
            return login;
        }

        public CheckItem getStorage() {
            return storage;
        }

        public boolean isHealthy() {
            return server.status == Status.OK && login.status == Status.OK && storage.status == Status.OK;
        }

        public String toSupportText() {
            return String.join("\n",
                    format(server),
                    format(login),
                    format(storage));
        }

        private static String format(CheckItem item) {
            String detail = item.technicalDetail.isBlank() ? "" : " (" + item.technicalDetail + ")";
            return item.title + ": " + item.message + detail;
        }
    }

    private SelfCheckService() {
    }

    public static Result runCheck() {
        CheckItem storage = checkStorage();
        String serverUrl = AppConfig.getServerUrl();
        try {
            URI uri = URI.create(serverUrl);
            if (uri.getScheme() == null || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                CheckItem server = new CheckItem(Status.ERROR, "服务器连接",
                        "服务器地址不正确，请修改后重试", serverUrl);
                CheckItem login = new CheckItem(Status.WARNING, "登录状态",
                        "尚未检查", "等待服务器地址修复");
                return new Result(server, login, storage);
            }
        } catch (Exception e) {
            CheckItem server = new CheckItem(Status.ERROR, "服务器连接",
                    "服务器地址不正确，请修改后重试", serverUrl);
            CheckItem login = new CheckItem(Status.WARNING, "登录状态",
                    "尚未检查", "等待服务器地址修复");
            return new Result(server, login, storage);
        }

        BatchRecordsService.Result probe = BatchRecordsService.fetch();
        CheckItem server;
        CheckItem login;
        switch (probe.getState()) {
            case AVAILABLE -> {
                server = new CheckItem(Status.OK, "服务器连接", "连接正常",
                        "HTTP " + probe.getHttpCode());
                login = new CheckItem(Status.OK, "登录状态", "登录有效", "Token accepted");
            }
            case UNAUTHORIZED -> {
                server = new CheckItem(Status.OK, "服务器连接", "服务器可以访问",
                        "HTTP " + probe.getHttpCode());
                login = new CheckItem(Status.ERROR, "登录状态", "请重新登录", probe.getMessage());
            }
            case NETWORK_ERROR -> {
                server = new CheckItem(Status.ERROR, "服务器连接", "暂时无法连接服务器",
                        probe.getMessage());
                login = new CheckItem(Status.WARNING, "登录状态", "尚未检查", "网络不可用");
            }
            case SERVER_ERROR, PARSE_ERROR -> {
                server = new CheckItem(Status.ERROR, "服务器连接", "服务器响应异常，请联系管理员",
                        probe.getMessage());
                login = new CheckItem(Status.WARNING, "登录状态", "尚未确认", probe.getMessage());
            }
            default -> throw new IllegalStateException("Unknown self-check state");
        }
        return new Result(server, login, storage);
    }

    private static CheckItem checkStorage() {
        Path directory = AppPaths.homeDirectory();
        Path probe = null;
        try {
            Files.createDirectories(directory);
            probe = Files.createTempFile(directory, ".write-check-", ".tmp");
            Files.writeString(probe, "ok");
            return new CheckItem(Status.OK, "本地存储", "可以正常保存数据", directory.toString());
        } catch (Exception e) {
            return new CheckItem(Status.ERROR, "本地存储", "无法保存数据，请检查目录权限",
                    e.getMessage());
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
