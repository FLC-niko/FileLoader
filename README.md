# FileLoader

国基形式审查系统的桌面文件上传助手（Compose Desktop 版）。

普通使用流程只有三步：登录、添加文件夹或 ZIP、等待处理完成后下载结果。选择文件夹后，程序会上传已有的顶层文件，并在本次运行期间继续自动上传新增文件。

同一时间只允许运行一个实例：重复打开时会提示“已经在运行”，并自动把已运行的窗口切到最前面，本次启动会被取消。

若退出登录，正在进行的上传会暂停并显示“已暂停，登录后自动继续”；重新登录后会自动继续，不需要重新添加文件夹。

## 项目结构

项目已整理为双模块结构：

- `core`：监控、上传下载服务、配置、数据库、模型与工具类。
- `app-compose`：Compose Desktop 界面与应用入口。

运行时配置与本地数据库默认存放在用户目录：

- `~/.fileloader/fileLoader.properties`
- `~/.fileloader/batches.db`
- `~/.fileloader/logs/`（程序日志）
- `~/.fileloader/instance.lock`（单实例锁）
- `~/.fileloader/instance.port`（已运行实例的监听端口）

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
- 运行测试：
  - macOS/Linux: `./gradlew test`
  - Windows: `gradlew.bat test`

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
- 默认连接学校正式服务 `https://xssc.gdut.edu.cn`；若把地址写成该校域名但用 `http`，启动时会自动规范化为 `https`。
- 工作室等自定义服务器地址（如 `http://10.21.76.73:8081/`）会原样保留，以便连接测试环境；修改服务器地址会清空当前登录态，需要重新登录。
- “检查连接”通过只读的 `batchRecords` 接口验证服务器与登录状态，不会上传测试文件。
- 单实例保护基于 `~/.fileloader/instance.lock` 的文件锁实现，进程异常退出时锁会由系统自动释放，无需手动清理。
