# 项目开发规则

## 固定技能

每次处理本项目任务，必须读取 `src/main/resources/skills/fanqie-novel-skill/SKILL.md`。涉及文风、检查、状态维护或剧情规划时，还需读取该技能引用的相关 references 文件。

该技能固定来自 `304769384-png/fanqie-novel-skill`，源版本和提交记录在同目录的 `origin.properties`。保留上游文件原文；适配逻辑由 Java 业务模块实现。升级技能需要明确记录源版本、变更内容及验证结果。

工程开发遵循 README 中的业务流程。小说创作任务按技能读取当前状态、大纲、伏笔、交接记录和质量记录；这些资料由数据库中的当前版本生成。用户已经确定的持续连载、阶段审核、重要变更审批及费用额度持续有效。

## 实现与验证

- 后端遵循 MVC 分层：controller 处理 HTTP，dto 定义请求，service 处理业务，repository 处理数据库访问；模型接入放在 integration/model，任务调度放在 engine，通用工具放在 support。
- Controller 通过 service 访问业务数据；JdbcTemplate 保持在 repository 内部。
- 每次小说模型调用都必须加载该技能主文件及对应角色参考资料，记录版本和内容指纹。
- 正文与记忆通过检查后，在同一事务内更新正式状态；保留历史版本和执行记录。
- 测试使用真实数据库、graph 和 HTTP 接口；真实模型验证使用供应商 API。
- API key、运行数据库和日志保留在 Git 忽略范围内。
- 功能变更同步更新 README 与 CHANGELOG，完成适当验证后提交 Git 并推送现有远程分支。
