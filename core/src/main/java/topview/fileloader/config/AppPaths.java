package topview.fileloader.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用数据目录。生产环境默认使用 ~/.fileloader；测试可通过
 * fileloader.home 指向临时目录，避免污染真实用户配置。
 */
public final class AppPaths {
    public static final String HOME_OVERRIDE_PROPERTY = "fileloader.home";

    private AppPaths() {
    }

    public static Path homeDirectory() {
        String override = System.getProperty(HOME_OVERRIDE_PROPERTY, "").trim();
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".fileloader");
    }
}
