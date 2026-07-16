# FileLoader

国基形式审查系统的桌面文件上传助手（Compose Desktop 版）。

普通使用流程只有三步：登录、添加文件夹或 ZIP、等待处理完成后下载结果。选择文件夹后，程序会上传已有的顶层文件，并在本次运行期间继续自动上传新增文件。

## 项目结构

项目已整理为双模块结构：

- `core`：监控、上传下载服务、配置、数据库、模型与工具类。
- `app-compose`：Compose Desktop 界面与应用入口。

运行时配置与本地数据库默认存放在用户目录：

- `~/.fileloader/fileLoader.properties`
- `~/.fileloader/batches.db`

程序会在首次启动时自动尝试迁移根目录历史文件（若存在）。

## 环境要求

- JDK 17+
- Gradle Wrapper（项目自带 `gradlew` / `gradlew.bat`）

## 常用命令

在项目根目录执行：

- 启动 Compose 应用：
  - macOS/Linux: `./gradlew :app-compose:run`
  - Windows: `gradlew.bat :app-compose:run`
- 一键构建 Compose JAR 到 `dist`：
  - macOS/Linux: `./gradlew buildComposeJarToDist`
  - Windows: `gradlew.bat buildComposeJarToDist`
- 构建跨平台 uber JAR 到 `dist`：
  - macOS/Linux: `./gradlew buildComposeUniversalJarToDist`
  - Windows: `gradlew.bat buildComposeUniversalJarToDist`
- 构建全部模块：
  - macOS/Linux: `./gradlew build`
  - Windows: `gradlew.bat build`

## Windows EXE 打包

在 Windows 机器执行：

```bash
gradlew.bat :app-compose:packageReleaseExe
```

产物默认目录：

- `app-compose/build/compose/binaries/main-release/exe/`

说明：EXE 打包依赖 `jpackage`，需在 Windows 环境执行。

## 说明

- 旧 Swing 界面与 `runSwing` 任务已移除。
- 入口类为 `topview.fileloader.app.ExeMainKt`，默认启动 Compose。
- 桌面端使用 `/api/desktop/**` Token API；登录参数和批次上传的 `batchId` 按最新版接口放在 query 中。
- 默认连接学校正式服务 `https://xssc.gdut.edu.cn`；旧工作室 IP 配置会在启动时自动迁移。
- “检查连接”通过只读的 `batchRecords` 接口验证服务器与登录状态，不会上传测试文件。
