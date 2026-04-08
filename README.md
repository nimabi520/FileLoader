# 文件自动上传系统 - 使用说明

欢迎使用文件自动上传系统！本工具可以帮助您自动将指定文件夹内的文件上传到服务器。即使您不熟悉电脑操作，只需按照以下简单步骤即可轻松使用。

## 🎯 给老师直接使用（推荐）

项目已提供可直接运行的单文件程序：`dist/FileLoader-standalone.jar`。

- macOS / Windows：安装 Java 17+ 后可尝试直接双击 `FileLoader-standalone.jar`
- 如果双击未启动：在终端执行 `java -jar dist/FileLoader-standalone.jar`

> 说明：该 jar 已包含运行所需依赖（含 SQLite 驱动），可直接分发给老师使用。

## 🧪 开发版（Compose 迁移阶段）

项目已切换到 Compose Desktop 前端重写阶段，新增了 Gradle 启动方式：

- 启动 Compose 迁移界面：`./gradlew run`
- 启动旧版 Swing 主界面：`./gradlew runSwing`

如果本机未安装 Gradle，可直接使用项目自带的 wrapper（`gradlew` / `gradlew.bat`）。

## 🚀 第一步：启动程序

1. 找到您下载并解压好的项目文件夹。
2. 根据您的系统双击对应启动脚本：
	- Windows：`start-windows.bat`
	- macOS：`start-mac.command`
3. 程序会自动编译并启动主界面；启动后命令行窗口会自动关闭。

## 📂 第二步：选择要监听的文件夹

程序启动后，您会看到一个操作界面。

1. 点击界面上的 **“浏览”** 按钮。
2. 在弹出的窗口中，找到并选中您想要用来存放待上传文件的文件夹（例如桌面上的“待上传作业”文件夹）。
3. 点击“确定”或“选择文件夹”。

## ➕ 第三步：添加到监听队列

1. 选好文件夹后，点击界面上的 **“添加文件夹”** 按钮。
2. 您会看到该文件夹的路径出现在了下方的列表中，这表示系统已经开始“盯着”这个文件夹了。

## 📤 第四步：自动上传文件

一切准备就绪！现在您可以开始工作了：

1. 只要您把任何需要上传的文件（如 Word 文档、图片等）**复制或移动** 到您刚才添加的那个文件夹里。
2. 系统就会自动发现新文件，并帮您上传到服务器。
3. 您可以在程序的界面上看到上传的进度和结果。

**💡 小贴士：**
* 您可以重复第二步和第三步，添加多个不同的文件夹让系统同时监听。
* 只要程序没有关闭，它就会一直默默地帮您上传新放入的文件。
* 如果您不想再监听某个文件夹，可以在列表中选中它，然后点击“移除”或相应的删除按钮。

---

## \ud83d\udd27 SQLite 驱动说明（可选）

如果您希望“批次历史记录”在重启后仍然保留，需要 SQLite 驱动：

1. 下载 `sqlite-jdbc-*.jar`
2. 在项目根目录创建 `lib` 文件夹（若不存在）
3. 把 jar 放到 `lib` 目录下（例如 `lib/sqlite-jdbc-3.46.0.0.jar`）

本项目已配置 VS Code 自动引用 `lib/**/*.jar`。如果未放置驱动，程序会自动进入“不持久化模式”，不影响上传和批次状态查询功能。

---

## ▶️ 一键启动（Windows / macOS）

项目根目录已提供跨平台启动脚本：

- Windows：双击 `start-windows.bat`
- macOS：双击 `start-mac.command`

脚本会自动执行以下步骤：

1. 检查 `java` 是否可用（需 JDK 17+）
2. 优先使用项目内 `gradlew`（不存在时回退系统 `gradle`）
3. 通过 `gradle run` 后台启动 Compose 迁移界面

启动完成后命令行窗口会自动关闭，不影响主程序继续运行。

如果是首次在 macOS 双击启动失败，请先在终端执行一次：

```bash
chmod +x start-mac.command
```

---

## 🧱 开发者：重新打包单文件 jar

如需重新生成可分发的可执行 jar，可在项目根目录执行：

```bash
rm -rf build dist
mkdir -p build/classes dist
javac -encoding UTF-8 -cp "lib/*" -d build/classes *.java
for j in lib/*.jar; do [ -f "$j" ] && (cd build/classes && jar xf "../../$j"); done
if [ -d build/classes/META-INF ]; then
	find build/classes/META-INF -type f \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' \) -delete
fi
jar cfe dist/FileLoader-standalone.jar topview.fileloader.MonitorUI -C build/classes .
```

生成产物：`dist/FileLoader-standalone.jar`