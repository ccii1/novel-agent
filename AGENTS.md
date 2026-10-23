# 项目开发规则

## 固定技能

每次处理本项目任务，必须读取 `src/main/resources/skills/fanqie-novel-skill/SKILL.md`。涉及文风、检查、状态维护或剧情规划时，还需读取该技能引用的相关 references 文件。

该技能固定来自 `304769384-png/fanqie-novel-skill`，源版本和提交记录在同目录的 `origin.properties`。保留上游文件原文；适配逻辑由 Java 业务模块实现。升级技能需要明确记录源版本、变更内容及验证结果。

工程开发遵循 README 中的业务流程。小说创作任务按技能读取当前状态、大纲、伏笔、交接记录和质量记录；正式记忆从已发布的 Markdown 文件版本读取，大纲与质量记录由批准的业务数据提供。用户已经确定的持续连载、阶段审核、重要变更审批及费用额度持续有效。

## 实现与验证

- 后端遵循 MVC 分层：controller 处理 HTTP，dto 定义请求，service 处理业务，repository 处理数据库访问；模型接入放在 integration/model，Redis 缓存放在 integration/cache，关系图查询放在 integration/graph，数据库类型识别放在 integration/storage，任务调度放在 engine，通用工具放在 support。
- Controller 通过 service 访问业务数据；数据库访问使用 MyBatis，SQL 位于 resources/mappers，事务由 Spring 管理。
- HTTP 请求类型放在 dto/ApiRequests，响应类型按业务放在 dto/response；Controller 返回明确类型。查询 Map 经 ResponseMapper 输出声明字段，金额使用 BigDecimal，未知用量保留 null。注释说明事务、并发、恢复和权限边界的原因。
- Agent 执行使用 AgentScope Java，生命周期扩展使用 MiddlewareBase；Redis 接入使用 Redisson。费用记录与任务状态保存在关系数据库。
- 独立 Agent 组件放在 agent 包，通过 NovelAgent 注册任务类型并声明上下文清单；AgentService 只负责注册、分发与分析查询。角色不得保存作品的可变会话状态，执行上下文按任务创建。
- Agent 输出结构在 dto/AgentOutputs 中声明，通过 agent/validation 下的 AgentOutputReader 和 Bean Validation 检查；大纲与审核证据校验也放在该目录，审核编排放在 agent/orchestration。正文版本等业务规则由相应模块维护。模型调用和记忆读写使用具体 Mapper 方法。
- 每次小说模型调用都必须加载该技能主文件及对应角色参考资料，记录版本和内容指纹。
- 正文与记忆通过检查后，准备并持久化不可变 Markdown 文件，再在同一事务内更新文件版本清单、正式章节与进度；保留历史版本和执行记录。
- 测试使用真实数据库、graph 和 HTTP 接口；真实模型验证使用供应商 API。
- API key、运行数据库和日志保留在 Git 忽略范围内。
- 功能变更同步更新 README 与 CHANGELOG，完成适当验证后提交 Git 并推送现有远程分支。
