package topview.fileloader.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.ConnectionSpec;
import okhttp3.CipherSuite;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;
import topview.fileloader.config.AppConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;

/** Shared, certificate-validating HTTP transport for every desktop API call. */
final class DesktopApiClient {
    private static final int MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;
    private static final String SCHOOL_HOST = "xssc.gdut.edu.cn";
    static final Gson GSON = new Gson();

    private static final ConnectionSpec SCHOOL_TLS = new ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384)
            .build();

    private static volatile ClientSet clients;

    private DesktopApiClient() {
    }

    static HttpUrl.Builder urlBuilder(String endpoint) {
        HttpUrl base = HttpUrl.parse(AppConfig.getServerUrl());
        if (base == null) {
            throw new IllegalArgumentException("服务器地址不正确");
        }
        HttpUrl.Builder builder = base.newBuilder();
        String basePath = base.encodedPath();
        if (!basePath.endsWith("/")) {
            builder.addPathSegment("");
        }
        String path = endpoint == null ? "" : endpoint.trim();
        while (path.startsWith("/")) path = path.substring(1);
        if (!path.startsWith("api/desktop/")) path = "api/desktop/" + path;
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) builder.addPathSegment(segment);
        }
        return builder;
    }

    static Request.Builder requestBuilder(HttpUrl url, boolean authenticated) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "FileLoader/2.0");
        if (authenticated) {
            String token = AppConfig.getAuthToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
        }
        return builder;
    }

    static Request.Builder requestBuilder(String endpoint, boolean authenticated) {
        return requestBuilder(urlBuilder(endpoint).build(), authenticated);
    }

    static Response execute(Request request) throws IOException {
        return clientFor(request.url()).newCall(request).execute();
    }

    static String readResponseBody(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return "";
        long declaredLength = body.contentLength();
        if (declaredLength > MAX_JSON_BODY_BYTES) {
            throw new IOException("服务器响应过大");
        }
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_JSON_BODY_BYTES) throw new IOException("服务器响应过大");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8).trim();
        }
    }

    static void clearAuthIfUnauthorized(int httpCode, String body) {
        try {
            if (isUnauthorized(httpCode, parseObject(body))) AppConfig.clearAuthSession();
        } catch (Exception e) {
            if (httpCode == 401) AppConfig.clearAuthSession();
        }
    }

    static String friendlyNetworkMessage(IOException error) {
        if (error instanceof SSLHandshakeException) {
            return "无法建立安全连接，请检查系统时间或联系技术人员检查服务器 TLS 配置";
        }
        if (error instanceof SocketTimeoutException) {
            return "连接服务器超时，请检查网络后重试";
        }
        if (error instanceof java.net.UnknownHostException) {
            return "无法解析服务器地址，请检查网络或服务器地址";
        }
        if (error instanceof ConnectException) {
            return "无法连接服务器，请确认网络可用";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "网络连接失败"
                : "网络连接失败：" + error.getMessage();
    }

    private static OkHttpClient clientFor(HttpUrl url) {
        int connect = AppConfig.getConnectTimeout();
        int read = AppConfig.getReadTimeout();
        int write = AppConfig.getWriteTimeout();
        ClientSet current = clients;
        if (current == null || !current.matches(connect, read, write)) {
            synchronized (DesktopApiClient.class) {
                current = clients;
                if (current == null || !current.matches(connect, read, write)) {
                    clients = current = new ClientSet(connect, read, write);
                }
            }
        }
        return SCHOOL_HOST.equalsIgnoreCase(url.host()) ? current.school : current.standard;
    }

    private static OkHttpClient.Builder baseClient(int connect, int read, int write) {
        return new OkHttpClient.Builder()
                .connectTimeout(connect, TimeUnit.SECONDS)
                .readTimeout(read, TimeUnit.SECONDS)
                .writeTimeout(write, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
    }

    private static final class ClientSet {
        final int connect;
        final int read;
        final int write;
        final OkHttpClient standard;
        final OkHttpClient school;

        ClientSet(int connect, int read, int write) {
            this.connect = connect;
            this.read = read;
            this.write = write;
            this.standard = baseClient(connect, read, write).build();
            this.school = this.standard.newBuilder()
                    // Internal school traffic must not be sent through Clash/system proxy ports such as 7890.
                    .proxy(Proxy.NO_PROXY)
                    .connectionSpecs(List.of(SCHOOL_TLS))
                    .build();
        }

        boolean matches(int connect, int read, int write) {
            return this.connect == connect && this.read == read && this.write == write;
        }
    }

    static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        JsonElement element = JsonParser.parseString(json);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static int getInt(JsonObject object, String field, int defaultValue) {
        JsonElement value = object == null ? null : object.get(field);
        if (value == null || value.isJsonNull()) return defaultValue;
        try { return value.getAsInt(); } catch (Exception e) { return defaultValue; }
    }

    static String getString(JsonObject object, String field) {
        JsonElement value = object == null ? null : object.get(field);
        if (value == null || value.isJsonNull()) return "";
        try { return value.getAsString(); } catch (Exception e) { return ""; }
    }

    static boolean getBoolean(JsonObject object, String field, boolean defaultValue) {
        JsonElement value = object == null ? null : object.get(field);
        if (value == null || value.isJsonNull()) return defaultValue;
        try { return value.getAsBoolean(); } catch (Exception e) { return defaultValue; }
    }

    static JsonObject getObject(JsonObject object, String field) {
        JsonElement value = object == null ? null : object.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String responseMessage(JsonObject root, int httpCode, String fallback) {
        String message = getString(root, "msg");
        if (!message.isBlank()) return message;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return httpCode > 0 ? "HTTP " + httpCode : "请求失败";
    }

    static boolean isUnauthorized(int httpCode, JsonObject root) {
        return httpCode == 401 || getInt(root, "code", Integer.MIN_VALUE) == 401;
    }
}
