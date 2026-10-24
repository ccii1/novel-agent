# 小说 Agent 流程与职责

依据版本：1.16.0；核查日期：2026-10-23。

## 用户身份与隔离

用户登录后只能访问自己的作品、选题、任务与费用。后台任务保存所属用户，执行时恢复用户范围并在结束时清理；Agent 工具核对任务与作品归属。记忆缓存按用户、作品和版本区分。已有数据归入本地 owner 账号，登录信息保存在 Git 忽略文件 `config/auth-local.json`。会话在服务重新启动后需要重新登录，任务与业务数据继续持久化保存。

## 逐章创作流程

```mermaid
flowchart TD
    Research["ResearchAgent：选题研究与讨论"] --> Create["用户选择题材并创建小说"]
    Create --> Plan["PlannerAgent：生成阶段大纲"]
    Plan --> ApprovePlan["人工确认阶段大纲"]
    ApprovePlan --> Write["WriterAgent：创作当前章节"]
    Write --> Review["章节审核：人物、剧情、节奏、文风"]
    Review --> ReviewResult{"全部审核通过？"}
    ReviewResult -->|通过| Extract["MemoryExtractionAgent：提取待确认记忆"]
    ReviewResult -->|需要修改| Reflect["ReflectionAgent：汇总修改要求"]
    Reflect --> Boundary{"重大变更或达到自动修改限制？"}
    Boundary -->|否| Rewrite["RewriterAgent：修改正文"]
    Boundary -->|是| HumanDraft["人工提供修改要求并确认范围"]
    HumanDraft --> Rewrite
    Rewrite --> Review
    Extract --> MemoryCheck["MemoryCheckAgent：审核待确认记忆"]
    MemoryCheck --> MemoryResult{"记忆审核结果"}
    MemoryResult -->|通过且满足批准条件| Commit["事务提交：正式记忆、章节确认、创作进度"]
    MemoryResult -->|允许自动修复| Repair["MemoryRepairAgent：修复待确认记忆"]
    Repair --> MemoryCheck
    MemoryResult -->|重要变更或达到修复限制| HumanMemory["人工确认或修改待确认记忆"]
    HumanMemory --> MemoryCheck
    Commit --> Stage{"阶段是否完成？"}
    Stage -->|否| Write
    Stage -->|是| HumanStage["人工阶段审核"]
    HumanStage -->|继续创作| Plan
```

选题研究可以使用个人想法及公开资料地址，也可以直接创建小说。阶段大纲生成后等待人工批准；当前阶段每章完成后自动开始下一章，阶段完成后等待人工审核，再进入下一阶段规划。

正文创作结果立即保存为草稿。审核通过后才进入记忆提取；正式记忆提交成功后，章节才成为已确认章节。当前流程没有自动发布到小说平台的操作。

## Agent 职责

| 角色 | 任务类型 | 主要职责与输出 |
| --- | --- | --- |
| ResearchAgent | `RESEARCH`、`RESEARCH_CHAT` | 生成选题方案，结合用户反馈继续讨论 |
| PlannerAgent | `PLAN` | 生成阶段目标、核心剧情节点及连续章节安排 |
| WriterAgent / RewriterAgent | `WRITE`、`REWRITE` | 创作或修改当前章节正文 |
| CharacterCheckAgent | `CHECK_CHARACTER` | 检查人物行为、状态与信息边界 |
| PlotCheckAgent | `CHECK_PLOT` | 检查因果、剧情连续性与伏笔安排 |
| PacingCheckAgent | `CHECK_PACING` | 检查冲突、节奏与叙事表现 |
| StyleCheckAgent | `CHECK_STYLE` | 检查表达、文风与写作规则 |
| ChapterReviewAgent | `CHECK_CHAPTER` | 联合检查人物、剧情、节奏、文风 |
| ReflectionAgent | `REFLECT` | 汇总审核意见，生成统一修改要求并标记重大变更 |
| MemoryExtractionAgent | `MEMORY`、`MEMORY_REPAIR` | 整理或修复 Markdown 主题文件、本章摘要与来源 |
| MemoryCheckAgent | `CHECK_MEMORY` | 逐项审核文件修改，检查本章遗漏及跨文件一致性 |
| PlanChangeAgent | `PLAN_CHANGE` | 为选定大纲字段生成局部修改建议与影响分析 |
| CoordinatorAgent | `COORDINATE` | 分析用户修改要求，选择正文、大纲或记忆处理角色 |

表中的角色名称表示执行时的职责。WriterAgent 与 RewriterAgent 由 `ChapterAuthorAgent` 组件处理，记忆提取与修复由 `MemoryExtractionAgent` 组件处理，多种审核角色由 `ReviewAgent` 组件提供。每个任务均创建独立的 Agent 执行实例与上下文。

## 章节审核与修改

默认模式为 `parallel`，四个审核任务独立排队，由工作线程并发执行。实际并发数量受可用线程与供应商限流约束。四项审核使用同一章节版本与记忆版本，`ReviewOrchestrator` 等待本轮结果完整后决定后续任务。

配置 `NOVEL_REVIEW_MODE=combined` 时，由一个 `ChapterReviewAgent` 联合检查四个方面。已经创建的审核轮次保持原有模式。

- 全部通过：进入记忆提取。
- 存在问题：进入 `ReflectionAgent`，形成统一正文修改要求。
- 普通修改：创建 `REWRITE`，完成后重新审核正文。
- 涉及重大变更：进入 `AWAITING_CHANGE`，等待人工确认。
- 正文自动修改达到三轮：进入 `AWAITING_HELP`，等待人工提供要求。

审核证据通过正文段落路径等信息定位，由程序校验引用来源；不符合证据规则的输出会使任务失败，并保留模型响应。

## 记忆处理与人物关系

```mermaid
flowchart LR
    Body["当前章节正文"] --> Delta["候选 Markdown 与修改清单"]
    Existing["当前版本的主题文件"] --> Check["MemoryCheckAgent：文件审核及遗漏检查"]
    Delta --> Check
    Check -->|通过| Prepare["准备不可变文件"]
    Prepare --> Transaction["数据库事务：登记发布清单"]
    Transaction --> Facts["正式记忆版本加一"]
    Transaction --> Chapter["章节标记为 ACCEPTED"]
    Transaction --> Progress["推进章节进度"]
    Check -->|普通问题| Repair["修复待确认记忆"]
    Repair --> Check
```

整理结果包含摘要与文件修改，文件按人物、世界规则、伏笔、支线、阶段和章节组织。稳定标识、别名与正文来源写入 Markdown；推测、人物认知、计划和已确认事实分别表达。

记忆审核发现表述问题时，自动修复对象是待确认记忆，正文与大纲保持当前版本。最多自动修复三轮；重要变更或超过限制后，在概况页面提供人工处理入口，可以提交修改要求，也可以直接编辑记忆后重新审核。

正式提交前再次检查章节编号、正文 revision、memoryVersion、候选指纹和旧文件指纹。文件在事务外准备，发布清单、章节确认、进度及下一任务在同一数据库事务中完成。

写作上下文包含短入口、当前状态、交接与本章明确相关的主题文件。Agent 通过授权的文件目录、关键词搜索、范围读取及正文来源工具补充资料，查询范围绑定当前用户、作品与任务版本。

人物关系与知情范围保存在 Markdown，关系文件使用 `story-relations` JSON 代码围栏提供明确的图记录，人物文件通过链接引用。启用 Neo4j 后，首次查询一个已发布版本时建立独立图投影，后续在数据库中执行限定深度查询；用户、作品和版本分别隔离，关系解除与历史修改通过新版本保留。旧关系附录继续支持读取。关闭 Neo4j 时使用进程内 TinkerGraph。

## 用户修改要求与协调委派

```mermaid
flowchart TD
    Request["概况：输入修改要求"] --> Coordinator["CoordinatorAgent：分析可用处理范围"]
    Coordinator --> Tool["调用一次委派选择工具"]
    Tool --> Proposal["保存建议：处理对象、要求、原因"]
    Proposal --> Human["人工确认委派"]
    Human --> Validate["事务核对大纲、阶段、章节、正文与记忆版本"]
    Validate --> Draft["DRAFT：创建 REWRITE"]
    Validate --> Outline["OUTLINE：创建 PLAN_CHANGE"]
    Validate --> Memory["MEMORY：创建 MEMORY_REPAIR"]
    Draft --> Review["正文审核流程"]
    Outline --> Preview["展示所选字段修改前后与关联影响"]
    Preview --> Approve["人工确认后合并大纲并保存版本"]
    Memory --> MemoryCheck["记忆审核流程"]
```

协调入口在人工审核或暂停状态使用，并要求当前相关任务完成。CoordinatorAgent 按当前状态选择一个可执行对象，通过 `delegate_draft`、`delegate_outline` 或 `delegate_memory` 表达选择。

委派工具生成建议；人工确认后，后端创建独立的持久化任务。重复确认或版本变化会被拒绝。CoordinatorAgent 只在用户提交修改要求时参与，正常逐章创作由业务工作流推进。

局部大纲修改支持阶段目标、核心剧情节点及章节目标、人物、伏笔字段。页面展示修改前后与影响范围，确认后只合并所选字段。涉及核心剧情或已完成章节时，需要明确确认对应范围。已经完成的正文与记忆调整通过历史修改流程处理。

## 框架与后端编排

```mermaid
flowchart TD
    API["Controller / Service：用户操作与业务规则"] --> Tasks["TaskScheduler：持久化任务"]
    Tasks --> Engine["TaskEngine：认领与并发执行"]
    Engine --> Registry["AgentService：按任务类型选择角色"]
    Registry --> Factory["AgentRuntimeFactory：独立 ReActAgent 与 Toolkit"]
    Factory --> Model["AgentScope：模型推理与工具调用"]
    Model --> Accounting["逐次请求：限流、预算与费用记录"]
    Accounting --> Provider["模型供应商"]
    Model --> Result["输出结构与业务证据检查"]
    Result --> Workflow["WorkflowService：事务更新与后续任务"]
    Workflow --> Tasks
    Workflow --> Reviews["ReviewOrchestrator：审核任务与结果汇集"]
    Reviews --> Tasks
```

AgentScope Java 2.0.4 负责 Agent 构建、推理、Toolkit 与生命周期事件。各角色声明独立职责、工具权限和推理轮数；`ModelClient` 加载固定写作技能、对应参考资料及任务上下文。

`WorkflowService` 决定正文、审核、记忆和下一章之间的状态变化。`ReviewOrchestrator` 管理审核角色、审核模式和结果汇集。协调委派经人工确认后创建后台任务；并行审核通过持久化调度执行。

带工具的角色当前最多两轮推理、三次模型请求、八次工具调用；没有工具的角色最多一次模型请求。每次请求的框架尝试次数为一次。

## 持久化、恢复与观测

| 数据 | 保存与使用方式 |
| --- | --- |
| 任务输入、结果、错误与状态 | 数据库保存，用于调度、执行详情与恢复 |
| 正文、历史版本与大纲修改 | 数据库保存；正式状态通过事务更新 |
| 正式记忆与历史文件 | 不可变 Markdown 文件；数据库登记发布清单、指纹和当前版本 |
| Agent 执行事件与耗时 | 数据库保存，前端执行记录与 Agent 看板查询 |
| 单次 HTTP 请求与用量 | `model_call_turns` 保存请求、响应、token、缓存用量、时间及估算费用 |
| 任务级模型调用 | `model_calls` 保存最终响应检查点与合计用量、费用 |
| 共享限流与记忆缓存 | Redisson / Redis；数据库保存正式业务状态 |

完整的最终 Agent 输出可以作为检查点复用。中断调用的费用无法确认时，任务需要人工核对费用后才能重新执行。失败任务可以重新选择模型，原执行记录与费用继续保留。

MySQL 与已有 H2 数据均有适配；已有作品数据是否使用 MySQL，取决于启动配置与迁移状态。现有作品数据尚未完成 MySQL 迁移。API key、运行数据库与日志保存在 Git 忽略范围内。

前端通过状态快照和事件编号自动同步变化。执行详情可以查看输入、输出、错误与逐次调用信息；Agent 看板提供角色权限、耗时、token 与费用记录。费用根据供应商配置和返回用量估算，供应商账单用于最终核对。

## 人工处理位置

| 触发条件 | 用户操作 |
| --- | --- |
| 阶段大纲生成完成 | 查看并批准当前阶段大纲 |
| 阶段内全部章节完成 | 审核阶段结果，决定继续创作 |
| 正文重大变更 | 确认修改范围并提供要求 |
| 正文自动修改达到限制 | 查看审核意见并提交修改要求 |
| 重要记忆变更或修复达到限制 | 确认、编辑记忆或提交记忆修改要求 |
| 协调建议生成完成 | 确认交给所选 Agent |
| 局部大纲修改建议生成完成 | 查看变更与影响后确认合并 |
| 预算暂停或调用费用待核对 | 调整额度或核对费用后继续 |

## 代码入口

- `src/main/java/dev/novel/service/WorkflowService.java`：主流程状态变化与记忆提交。
- `src/main/java/dev/novel/service/CoordinationService.java`：协调请求、人工确认与任务委派。
- `src/main/java/dev/novel/service/PlanChangeService.java`：局部大纲修改建议与确认。
- `src/main/java/dev/novel/agent/orchestration/ReviewOrchestrator.java`：审核模式、任务与结果汇集。
- `src/main/java/dev/novel/agent/runtime/AgentRuntimeFactory.java`：独立 Agent 与工具构建。
- `src/main/java/dev/novel/agent/runtime/StoryReadTools.java`：查询与委派工具。
- `src/main/java/dev/novel/engine/TaskEngine.java`：后台任务执行与恢复。
