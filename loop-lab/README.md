# Loop Lab

规格：[SDD-001](../docs/specs/001-agent-loop.md)。一个 ReActAgent、一个只读工具，每次运行使用独立会话。

## 运行

在项目根目录执行，Java 使用17。模型服务需要监听8317；配置来自已有的 `config/application-local.yml`，本单元使用 `local / gpt-6-luna / high`。

```powershell
Set-Location 'D:\java_learning\novel-agent'
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\mvnw.cmd -f loop-lab/pom.xml compile exec:java '-Dexec.args="D:/java_learning/novel-agent/config/application-local.yml" "D:/java_learning/novel-agent/loop-lab/notes.md" "调用read_notes，回答项目名和验证口令"'
```

终端依次显示模型请求、工具执行、后续模型请求和最终答案。工具需要由模型主动选择；API 请求携带工具说明，框架执行模型返回的工具调用，再把结果加入消息历史。

## 验证

本地文件与输入检查：

```powershell
.\mvnw.cmd -f loop-lab/pom.xml test
```

显式启用真实模型验证：

```powershell
$env:LOOP_LIVE='true'
.\mvnw.cmd -f loop-lab/pom.xml '-Dtest=AgentLoopLiveTest' test
Remove-Item Env:LOOP_LIVE
```

真实模型验证运行两个独立任务，分别检查直接回答、调用工具后回答。当前本地检查2项通过；真实模型验收因8317没有服务监听，连接失败，尚未通过。

## 代码阅读

1. `LoopMain`：取得三个参数并启动任务。
2. `LoopConfig`：读取已有配置，限定本机免费服务，隐藏凭据。
3. `AgentLoop`：构建模型、注册工具和 Middleware，交给 ReActAgent 执行循环。
4. `ReadNotesTool`：读取指定文件，检查字节数，记录调用与异常。

用户输入、工具返回和请求次数均有上限，因此本单元不需要摘要压缩。它没有跨任务记忆。token 用量来自每次模型响应，未提供时显示未知。

AC04 的工具次数、文件变化与异常保留已经本地验证。模型请求上限、180秒超时及 SDK 非正常结束拒绝逻辑经过代码检查，尚未通过真实模型触发各个限制条件。验收范围继续以规格和实际结果为准。
