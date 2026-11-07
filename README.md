# jenkins-atom-deploy-war

把 workspace 里的 WAR/EAR 交给一个已经在跑的 servlet 容器，并且**回读容器的应用列表**确认它真的在 `running`。
原插件是 `deploy`（Deploy to container，安装量 7,707）：post-build 步骤，选容器（Tomcat/JBoss/GlassFish/WebLogic）、
填地址与账号，插件交给 Cargo 去调各容器的远程部署接口。这里做同一件事，但不带 Cargo——容器管理接口本来就是 HTTP，
原子直接说这个协议：agent 上不需要装容器、不需要 Cargo、不需要 shell 出去。

## 用法

```bash
mvn verify   # build + src/test/java/atom/DeployWarIT.java（对着 tools/fake_manager.py）
```

输入：

- `url`：容器地址。三种形状都吃——`http://host:8080`（原子补 `/manager/text`）、插件的
  Tomcat URL 字段那种完整地址 `http://host:8080/manager/text`、以及 `url` + `managerPath` 分开给。
- `war`：路径或 Ant 风格 glob（`**/*.war`），从 workspace 读；匹配多个就逐个部署。
- `contextPath`：留空则按 Tomcat 的规矩用文件名（`shop-v2.war` → `/shop-v2`，`ROOT.war` → 根 `/`）；
  给了就只允许一个归档——一个 context path 命名不了两个应用，这种情况直接失败而不是猜。
  支持 `${BUILD_NUMBER}` / `$BUILD_NUMBER` 展开，展开不出来就失败，不会拿字面量去部署。
- `username` / `password`：Tomcat 的 manager 用户，需要 `manager-script` 角色。密码走凭据（secret），
  不进日志、不进命令行。
- `update`：默认 `true`，等价于插件的"重新部署"。关掉时同名应用已存在会失败并提示加 `update=true`。
- `timeoutSeconds`：单次上传超时。

出参：`deployed`（个数）、`contextPaths`（逗号分隔，ROOT 记作 `/`）、`states`（`/app=running`）、
`managerUrl`、`verified`。

## 为什么不信任 200

Tomcat 的 manager 在**失败时也回 200**，而且文案是本地化的。下面这段是本机真跑 Tomcat 10.1.61（中文 locale）
拿到的，不是猜的：

```
# 第二次部署同一个 path，没带 update=true
HTTP 200
失败 - 应用程序已存在于路径 [/atom-twice]

# 部署一个坏 war（不是 zip）
HTTP 200
失败 - 在上下文[/atom-broken]下部署应用程序，但是上下文启动失败。
# 而且列表里它已经存在了：
/atom-broken:stopped:0:atom-broken.war
```

所以这个原子不 grep 英文句子、也不认 200：

1. 回包第一行 `" - "` 前面那段必须是 `OK`（zh 成功是 `OK - 以应用path [...] 部署应用`，失败是 `失败 - ...`；
   en 失败是 `FAIL - ...`，两种都覆盖）；
2. 然后 `GET /manager/text/list` 回读，该 context path 必须在列表里**且状态是 `running`**。
   只"在册"不够——上面那个坏 war 就在册，状态是 `stopped`。

## 覆盖范围（诚实版）

- **覆盖**：Tomcat 的 Manager text API（Tomcat 7/8/9/10/11 的 `/manager/text`），本机用 10.1.61 真实验证过。
- **未覆盖**，原因写在这里而不是含糊过去：
  - **JBoss AS7/WildFly**：管理接口在 9990，上传要走 multipart `add` + `input-stream-index` + 307 跳转，
    形态与 Tomcat 完全不同；
  - **GlassFish**：原插件根本不走 HTTP——它要 GlassFish home 与 admin port，在本机用 `asadmin` 部署；
  - **WebLogic**：t3 协议 + WLST，没有可用的 HTTP 部署端点。
  这三家要真验证就得在本机起对应容器，没验证过就不写"已支持"。需要的话下一版单独加，一家一家带真实容器验证。

## smoke 验的是什么

`src/test/java/atom/DeployWarIT.java` 起一个假的 Tomcat manager（`tools/fake_manager.py`，按协议重写，不是照着原子写的），
外加一段可选的真实容器验证（给 `REAL_DEPLOY_URL` / `REAL_DEPLOY_USER` / `REAL_DEPLOY_PASSWORD` 就跑）。

假端点自己算该算的东西：它把收到的字节**自己哈希**并落盘，所以"部署上去的到底是不是 workspace 里那个文件"
是它说的，不是原子说的；`tools/check.py` 是第二实现（Python），独立重算 glob 匹配、context path 推导、
update 标志、请求该打到哪个 URL，再和 output.json、manager 的请求日志、manager 的应用注册表三方比对。

16 个场景：两个 war 自动推导 context path、显式 context path、`ROOT.war` → `/`、完整 manager URL、
输入拒绝（多个归档配一个 context path / 空 glob / context path 没有前导斜杠 / 没写 scheme）、
401、`--deny`、manager 路径写错、`update=false` 重复部署、500、`--no-register`（答 OK 但没注册）、
`--stopped`（注册了但没起来）、en/zh 两种 locale、`${BUILD_NUMBER}` 展开、以及一个"manager 少存 100 字节"
的坏端点——它必须被摘要比对抓住（这一条期望是红的，否则摘要比对就是摆设）。

两次变异检验：把状态检查改成 `if (false)` → 红在 `--stopped` 那条；把回包标记检查改成 `if (false)` →
红在 `update=false` 重复部署那条。

## 边界

不负责起容器、不构建 war（那是 `maven`/`gradle` 的事）、不管容器里的连接池与会话。只做"把归档交上去，
并证明它现在在跑"。
