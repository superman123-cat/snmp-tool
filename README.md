# SNMP MIB 管理与设备交互工具 — 部署文档

## 一、系统概述

基于 Web 的 MIB 管理与 SNMP 设备交互工具，支持 MIB 文件批量导入解析、交互式树状导航、多条件搜索、SNMP v1/v2c/v3 设备通信与查询结果可视化导出。

- **后端**：Java 11 + Spring Boot 2.7.18 + SNMP4J 3.6.x
- **前端**：原生 HTML5 / CSS3 / ES6+ JavaScript（无前端构建依赖）
- **架构**：前后端分离，RESTful API，Bearer Token 认证

## 二、环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 11 或以上（推荐 11.0.x） |
| 操作系统 | Windows / Linux / macOS |
| 内存 | ≥ 512MB（建议 1GB） |
| 浏览器 | Chrome / Firefox / Edge 最新版本 |
| 磁盘 | ≥ 100MB（含日志与 MIB 文件存储） |

> 无需额外安装数据库、Redis、Nginx 等依赖软件，开箱即用。

## 三、部署包内容

```
snmp-netconf-tool/
├── target/
│   └── snmp-netconf-tool.jar   # 可执行 JAR，已内嵌前端静态资源
├── sample/
│   └── TEST-MIB.mib            # 测试用 MIB 文件
├── README.md                   # 本部署文档
└── src/                        # 源代码（可选）
```

## 四、快速启动

### 4.1 直接运行 JAR

```bash
# 默认端口 8080
java -jar target/snmp-netconf-tool.jar

# 自定义端口
java -jar target/snmp-netconf-tool.jar --server.port=9090

# 后台运行（Linux）
nohup java -jar target/snmp-netconf-tool.jar --server.port=8080 > app.log 2>&1 &

# Windows 后台运行
start /b java -jar target/snmp-netconf-tool.jar --server.port=8080
```

启动成功后访问：`http://localhost:8080/`（或自定义端口）

### 4.2 默认账号

默认账号的密码**不会硬编码到源码或配置中**。首次启动时系统会为 `admin` 与 `operator` 各自随机生成一个高强度密码（20/18 位，混合大小写 + 数字 + 符号），并写入 `logs/admin-credentials.txt`（POSIX 0600 权限；Windows 请确保 logs 目录受 ACL 保护）。请在首次启动后立刻查看该文件并妥善保管。

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | 启动时随机生成，存于 `logs/admin-credentials.txt` | 全部功能（含设备增删改） |
| 操作员 | `operator` | 启动时随机生成，存于 `logs/admin-credentials.txt` | 查询与只读操作 |

> 旋转密码：删除 `logs/admin-credentials.txt` 后重启即可一次性重新生成两个账号；或通过环境变量 `APP_AUTH_ADMIN_PASSWORD`（12+ 字符，必须包含大小写字母+数字+符号）覆盖管理员密码。

## 五、从源码构建

如需从源码重新构建：

```bash
# 需要 Maven 3.6+ 与 JDK 11
mvn clean package -DskipTests
# 产物：target/snmp-netconf-tool.jar
```

## 六、功能验证清单

启动后按以下步骤验证系统功能：

1. **登录**：访问 `http://localhost:8080/`，使用 `logs/admin-credentials.txt` 中的账号登录
2. **MIB 导入**：左侧「MIB」标签 → 点击「导入 MIB」→ 上传 `sample/TEST-MIB.mib`
3. **树状导航**：上传完成后，左侧树状菜单显示 `testMIB` 根节点，可展开/折叠
4. **搜索**：顶部搜索框输入 `test` → 显示匹配的节点列表
5. **添加设备**：切换到「设备」标签 → 点击「添加设备」→ 填写 IP/端口/共同体名
6. **测试连接**：设备卡片右键 → 测试连接
7. **节点查询**：在 MIB 树节点上右键 → 「查询单个节点」或「查询整表」
8. **导出**：查询结果页面 → 点击「导出 CSV」或「导出 Excel」

## 七、配置说明

所有配置项可通过 `application.properties` 或命令行参数覆盖：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 服务端口，可命令行 `--server.port=PORT` 覆盖 |
| `app.mib.upload-dir` | `./mib-files` | MIB 文件存储目录 |
| `app.mib.max-file-size` | 10485760 (10MB) | 单个 MIB 文件大小上限（字节） |
| `app.auth.enabled` | true | 是否启用认证（生产环境必须 true） |
| `app.auth.session-timeout-minutes` | 60 | 会话超时时间（分钟） |
| `app.auth.admin.username` | admin | 管理员用户名 |
| `app.auth.credentials-file` | `./logs/admin-credentials.txt` | 自动生成的凭据文件路径（owner-only） |
| `spring.servlet.multipart.max-file-size` | 100MB | 单文件上传上限 |
| `spring.servlet.multipart.max-request-size` | 500MB | 单次请求总上传上限 |

### 修改默认密码

默认情况下 admin / operator 密码由系统启动时随机生成，存于 `logs/admin-credentials.txt`。旋转密码有两种方式：

**方式一：删除凭据文件后重启（旋转两个账号）**
```bash
rm logs/admin-credentials.txt
java -jar target/snmp-netconf-tool.jar
# 重新生成两个高强度密码，存回 logs/admin-credentials.txt
```

**方式二：通过环境变量覆盖管理员密码（推荐生产环境）**
```bash
# 长度 ≥ 12，必须包含大写字母、小写字母、数字、符号
export APP_AUTH_ADMIN_PASSWORD='MyStr0ng!Admin#2026'
java -jar target/snmp-netconf-tool.jar
```

> 明文密码只存在于您设置的环境变量中，启动后立即在内存中以 PBKDF2-HMAC-SHA256 哈希（120k 迭代、16 字节随机盐）保存，原文不会被持久化。

## 八、RESTful API 接口

所有接口以 `/api` 为前缀，需在请求头携带 `Authorization: Bearer <token>`（登录除外）。

### 认证
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回 token |
| POST | `/api/auth/logout` | 注销 |
| GET | `/api/auth/status` | 查询认证状态 |

### MIB 管理
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/mib/upload` | 批量上传 MIB（multipart/form-data，字段名 `files`） |
| GET | `/api/mib/upload/progress/{batchId}` | 查询上传解析进度 |
| GET | `/api/mib/modules` | 已解析模块列表 |
| GET | `/api/mib/tree` | 获取整棵 MIB 树 |
| GET | `/api/mib/node?name=&oid=` | 按名称或 OID 查节点 |
| GET | `/api/mib/search?keyword=&type=&limit=` | 多条件搜索 |
| GET | `/api/mib/stats` | 统计信息 |
| DELETE | `/api/mib/modules/{name}` | 删除模块 |

### 设备管理
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/devices` | 设备列表 |
| GET | `/api/devices/{id}` | 设备详情 |
| POST | `/api/devices` | 新建设备 |
| PUT | `/api/devices/{id}` | 更新设备 |
| DELETE | `/api/devices/{id}` | 删除设备 |
| POST | `/api/devices/{id}/test` | 测试连接 |
| POST | `/api/devices/refresh-all` | 批量刷新状态 |

### SNMP 操作
| 方法 | 路径 | 请求体 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/devices/{id}/get` | `{"oids":["1.3.6.1..."]}` | SNMP GET |
| POST | `/api/devices/{id}/getnext` | `{"oids":["1.3.6.1..."]}` | SNMP GETNEXT |
| POST | `/api/devices/{id}/getbulk` | `{"oid":"1.3.6.1...","nonRepeaters":0,"maxRepetitions":10}` | SNMP GETBULK |
| POST | `/api/devices/{id}/walk` | `{"oid":"1.3.6.1...","maxRows":200}` | 整表 Walk |

### 系统
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/system/info` | 系统信息 |

## 九、性能指标

| 指标 | 实测 |
| --- | --- |
| 启动时间 | ~2 秒 |
| 单 MIB 解析 | < 1 秒（10 节点） |
| 树状导航响应 | < 100ms |
| 搜索响应 | < 300ms |
| 简单 GET 查询 | < 500ms（取决于设备） |

## 十、日志与排错

- **日志文件**：`./logs/snmp-tool.log`
- **常见问题**：

| 现象 | 原因与解决 |
| --- | --- |
| 端口被占用 | `--server.port=新端口` 或停止占用进程 |
| 登录提示 401 | token 失效，重新登录 |
| MIB 解析失败 | 检查文件格式，确认 IMPORTS 引用的模块已先上传 |
| 设备连接超时 | 增大超时时间/重试次数，检查防火墙 |
| 中文乱码 | JVM 添加 `-Dfile.encoding=UTF-8` |

## 十一、生产环境加固建议

1. **启用 HTTPS**：配置 Spring Boot SSL 或前置 Nginx 反向代理
   ```properties
   server.ssl.enabled=true
   server.ssl.key-store=classpath:keystore.p12
   server.ssl.key-store-password=changeit
   ```
2. **修改默认密码**：默认密码启动时随机生成；管理员密码可通过环境变量 `APP_AUTH_ADMIN_PASSWORD` 覆盖，详见第七节
3. **限制内存**：`java -Xmx1g -jar snmp-netconf-tool.jar`
4. **日志轮转**：使用 logback 配置 `RollingFileAppender`
5. **反向代理**：生产环境建议 Nginx 前置，限制 `/api` 以外的访问

## 十二、技术支持

- 源码结构、模块说明、常见问题排查见项目源代码 `src/` 目录
- 测试 MIB 样例：`sample/TEST-MIB.mib`
