# 代码目录

| 目录 | 内容 |
| --- | --- |
| `agent` | 规划、写作、审核、记忆四个组件及上下文 |
| `agent/runtime` | AgentScope 实例与只读文件工具 |
| `agent/validation` | 输出字段、章纲与正文证据校验 |
| `service` | 章节流程、作品、记忆、费用和执行记录 |
| `controller` | HTTP 接口 |
| `repository`、`resources/mappers` | MyBatis 数据访问 |
| `integration/model` | 模型配置、请求、限流和上下文压缩 |
| `security` | 登录与作品所有权 |
| `frontend/src/pages` | 创建作品、作品工作区和费用页面 |
| `data` | 被 Git 忽略的正式业务数据 |

主要入口：

- `WorkflowService`：决定下一步骤，提交正文和记忆。
- `ProjectService`：创建、审批、暂停、恢复与失败重试。
- `MemoryPatchService`：应用唯一匹配的局部修改。
- `MemoryFileService`：不可变文件、发布清单、路径及引用校验。
- `AgentScopeExecutor`：执行模型与工具请求。
- `TaskEngine`：认领、恢复与执行持久化任务。

`CoreWorkflowIntegrationTest` 检查基础流程、真实 HTTP、账号隔离与任务范围。`MemoryPatchIntegrationTest` 检查局部修改与事务提交。`MarkdownMemoryIntegrationTest` 检查文件版本、引用及历史数据读取。费用、输出类型、正文证据和技能指纹使用对应测试验证。
