const state = {view: 'new', id: null, tab: 'overview', detail: null, idea: null, researchId: null};
state.researchChoice = null;
state.researchChatChoices = {};
state.observation = {novelId:'',chapter:'',kind:'',status:'',page:0};
const duration = value => value == null ? '未记录' : Number(value) < 1000 ? `${Math.round(value)} ms` : `${(Number(value) / 1000).toFixed(2)} s`;
const money = value => value == null ? '待确认' : `¥${Number(value).toFixed(6)}`;
const localTime = value => value ? new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}) : '尚未记录';
const labels = {PLANNING:'规划中',AWAITING_PLAN:'等待大纲审核',WRITING:'写作中',CHECKING:'并行检查中',REFLECTING:'制定修改计划',EXTRACTING:'提取创作记忆',CHECKING_MEMORY:'检查记忆变更',AWAITING_STAGE_REVIEW:'等待阶段审核',AWAITING_CHANGE:'等待重大变更审核',AWAITING_HELP:'需要人工处理',PAUSED:'已暂停',ERROR:'执行失败',BUDGET_PAUSED:'费用额度不足',WAITING:'等待执行',READY:'结果等待处理',RUNNING:'执行中',DONE:'已完成',CANCELLED:'已取消',DRAFT:'草稿',ACCEPTED:'检查通过',INVALIDATED:'等待重新生成',IN_FLIGHT:'调用中',COMPLETE:'已结算',UNCERTAIN:'费用待核对',RECONCILED:'已人工核对'};
const content = document.querySelector('#content');
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
const pretty = value => typeof value === 'string' ? JSON.stringify(JSON.parse(value), null, 2) : JSON.stringify(value, null, 2);
const badge = value => `<span class="badge">${escapeHtml(labels[value] || value)}</span>`;
async function api(path, method = 'GET', body) {
  const response = await fetch('/api' + path, {method, headers: body === undefined ? {} : {'Content-Type':'application/json'}, body:body === undefined ? undefined : JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `请求失败：${response.status}`);
  return data;
}
function notice(message, error = false) {
  const element = document.querySelector('#notice'); element.hidden = false; element.textContent = message; element.style.borderColor = error ? '#b76e64' : '#66724d';
}
async function sidebar() {
  const [novels, status, budget] = await Promise.all([api('/novels'),api('/status'),api('/budget')]);
  document.querySelector('#novelList').innerHTML = novels.map(novel => `<button class="novel-button ${state.id === novel.id ? 'active' : ''}" data-novel="${escapeHtml(novel.id)}">${escapeHtml(novel.title)}<small>${escapeHtml(labels[novel.status] || novel.status)}</small></button>`).join('') || '<small>尚未创建作品</small>';
  document.querySelector('#engineStatus').textContent = `${status.engine.active}/${status.engine.workers} 个任务执行中 · ${status.modelConfigured ? '模型已配置' : '模型尚未配置'} · 写作规范 v${status.skill.version}`;
  document.querySelector('#budgetStatus').textContent = `${budget.month} · 已用及预留 ¥${Number(budget.usedAndReserved).toFixed(2)} / ¥${budget.limit}`;
}
async function render() {
  document.querySelectorAll('nav [data-view]').forEach(button => button.classList.toggle('active',button.dataset.view === state.view));
  document.querySelector('#breadcrumb').textContent = {new:'创建作品',research:'市场资料与原创选题',budget:'费用与调用记录',novel:'作品管理',observability:'Agent 执行与效果'}[state.view];
  if (state.view === 'new') await renderNew();
  if (state.view === 'research') await renderResearch();
  if (state.view === 'budget') await renderBudget();
  if (state.view === 'novel') await renderNovel();
  if (state.view === 'observability') await renderObservation(content);
  await sidebar();
}
async function renderNew() {
  content.innerHTML = `<div class="intro"><h1>让故事持续生长</h1><p class="muted">选定创意，审核阶段大纲。写作、并行检查与修改由 Agent 持续执行，重要剧情变化交给你决定。</p></div>
  <form id="newForm" class="card compact"><h2>创建连载小说</h2><label>作品名称</label><input name="title" required maxlength="200" value="${escapeHtml(state.idea?.title || '')}"><label>故事创意与读者期待</label><textarea name="premise" required maxlength="12000">${escapeHtml(state.idea ? JSON.stringify(state.idea,null,2) : '')}</textarea><label>创作设定与限制</label><textarea name="bible" maxlength="12000" placeholder="世界规则、文风要求、需要避免的内容；可以留空，由阶段规划提出设定供审核。"></textarea>
  <div class="grid"><div><label>每阶段章节数量</label><input name="stageSize" type="number" min="1" max="20" value="10" required></div><div><label>本作品每月额度 / 元</label><input name="novelBudget" type="number" min="0.01" max="300" step="0.01" value="100" required></div><div><label>每章全部调用费用上限 / 元</label><input name="chapterBudget" type="number" min="0.01" max="100" step="0.01" value="5" required></div></div>
  <div id="newModelFields"></div><button>创建并规划</button></form>
  <details class="card compact"><summary>配置云端模型</summary><p>将 config/application-local.example.yml 复制为 config/application-local.yml，填写完整的 HTTPS Chat Completions 地址、API key、模型名称及人民币 token 单价，然后重新启动服务。</p><p>也可以通过 NOVEL_MODEL_ENDPOINT、NOVEL_MODEL_API_KEY、NOVEL_MODEL_NAME、NOVEL_INPUT_PRICE、NOVEL_OUTPUT_PRICE 环境变量配置。模型需要支持 JSON object 和 token 用量返回。密钥文件已加入 Git 忽略规则。</p></details>`;
  await modelFields(document.querySelector('#newModelFields'));
}
async function modelFields(container, provider, model = '', callbacks = {}) {
  const catalog = await api('/providers');
  provider = provider || catalog.defaultProvider;
  container.innerHTML = `<div class="grid"><div><label>模型服务</label><select name="provider">${catalog.providers.map(item => `<option value="${escapeHtml(item.id)}" ${item.id === provider ? 'selected' : ''}>${escapeHtml(item.label)} · ${item.billingMode === 'unmetered' ? '免费调用' : '按 token 计费'}${item.ready ? '' : '（待配置）'}</option>`).join('')}</select></div><div><label>文本模型</label><select name="model"></select></div></div><p class="muted">免费服务保留 token 用量；计费服务沿用原有预算。切换后用于后续调用，已有记录继续保留。</p>`;
  const services = container.querySelector('[name=provider]');
  const models = container.querySelector('[name=model]');
  let loadVersion = 0;
  const remember = () => callbacks.onChange?.({provider:services.value,model:models.value});
  async function loadModels(chosen = '') {
    const version = ++loadVersion;
    callbacks.onReady?.(false);
    models.innerHTML = '<option value="">正在读取模型列表…</option>';
    models.disabled = true;
    const profile = catalog.providers.find(item => item.id === services.value);
    if (!profile.ready) {
      models.innerHTML = '<option value="">服务尚未配置</option>';
      return;
    }
    const names = await api(`/providers/${encodeURIComponent(services.value)}/models`);
    if (version !== loadVersion || !container.isConnected) return;
    if (chosen && !names.includes(chosen)) names.unshift(chosen);
    models.innerHTML = `<option value="">服务默认（${escapeHtml(profile.defaultModel)}）</option>${names.map(name => `<option value="${escapeHtml(name)}" ${name === chosen ? 'selected' : ''}>${escapeHtml(name)}</option>`).join('')}`;
    models.disabled = false;
    remember();
    callbacks.onReady?.(true);
  }
  services.addEventListener('change', () => {
    callbacks.onChange?.({provider:services.value,model:''});
    loadModels().catch(error => notice(error.message, true));
  });
  models.addEventListener('change',remember);
  await loadModels(model);
}
async function researchModelFields(container,choice,remember,busy = false) {
  const submit = container.closest('form').querySelector('button');
  submit.disabled = true;
  try {
    const capabilities = await api('/status');
    if (!capabilities.researchModelSelection) throw new Error('请重启后端服务以启用选题模型选择');
    await modelFields(container,choice?.provider,choice?.model || '',{
      onChange:value => { if (container.isConnected) remember(value); },
      onReady:ready => { if (container.isConnected) submit.disabled = busy || !ready; }
    });
    if (busy && container.isConnected) container.querySelectorAll('select').forEach(select => { select.disabled = true; });
  } catch(error) { if (container.isConnected) { submit.disabled = true;notice(error.message,true); } }
}
async function renderResearch() {
  const [researches, tasks] = await Promise.all([api('/research'),api('/research/tasks')]);
  if (state.view !== 'research') return;
  state.researchSignature = JSON.stringify(tasks.map(task => [task.id,task.status]));
  state.researches = researches;
  if (!researches.some(report => report.id === state.researchId)) state.researchId = researches[0]?.id || null;
  content.innerHTML = `<div class="intro"><h1>好故事，从一个想法开始</h1><p class="muted">告诉 Agent 你想写什么。一起讨论人物、冲突与读者期待，找到值得持续创作的方向。</p></div>
    <div class="research-layout"><div class="research-compose"><form id="researchForm" class="card"><h2>你的创作想法</h2><label for="researchBrief">想写怎样的故事？</label><textarea id="researchBrief" name="brief" maxlength="4000" placeholder="例如：想写一个小城修理工卷入旧案的故事，主角靠专业技能调查。希望悬疑感强，有生活气息，感情线克制。"></textarea><p class="field-help">可以描述题材、人设、喜欢的情节，以及不想出现的内容。</p><details class="source-input"><summary>添加参考资料（可选）</summary><label for="researchUrls">番茄公开榜单或作品详情链接</label><textarea id="researchUrls" name="urls" placeholder="每行一个链接，最多五个"></textarea></details><button>开始研究选题 <span aria-hidden="true">→</span></button></form>
    <div class="report-history"><h3>之前的选题</h3>${researches.map(report => `<button type="button" data-report="${escapeHtml(report.id)}" class="report-link ${report.id === state.researchId ? 'active' : ''}"><strong>${escapeHtml(report.brief || JSON.parse(report.ideas)[0]?.title || '选题报告')}</strong><small>${escapeHtml(report.created_at)}</small></button>`).join('') || '<p class="muted">你的选题和交流会保存在这里。</p>'}</div></div>
    <div class="research-results">${tasks.filter(task => task.kind === 'RESEARCH' && !['DONE','CANCELLED'].includes(task.status)).map(task => `<div class="card research-progress"><h3>选题研究 ${badge(task.status)}</h3>${researchTaskProgress(task)}</div>`).join('')}<div id="researchReport"></div></div></div>`;
  content.querySelector('#researchForm>button').insertAdjacentHTML('beforebegin','<div id="researchModelFields" class="research-model-fields"></div>');
  await Promise.all([
    researchModelFields(document.querySelector('#researchModelFields'),state.researchChoice,value => {state.researchChoice = value;}),
    renderResearchReport()
  ]);
  await refreshResearchProgress();
}
async function renderResearchReport() {
  const panel = document.querySelector('#researchReport');
  if (!state.researchId) { panel.innerHTML = '<div class="research-empty"><div class="empty-symbol" aria-hidden="true">✧</div><h2>给灵感一点生长的空间</h2><p>写下一个人物、一段经历，或一种你想带给读者的感受。<br>Agent 会从这里提出三个故事方向。</p></div>';return; }
  const detail = await api(`/research/${state.researchId}`);
  if (state.view !== 'research' || detail.report.id !== state.researchId) return;
  const report = detail.report;
  const busy = detail.tasks.some(task => ['WAITING','RUNNING','READY','ERROR'].includes(task.status));
  const ideas = JSON.parse(report.ideas);
  panel.innerHTML = `<div class="report-heading"><div><h2>一起把故事想清楚</h2><p class="muted">三个故事方向，随着交流持续更新。</p></div><span class="report-count">${ideas.length} 个方案</span></div>
    <div class="idea-list">${ideas.map((idea,index) => `<article class="idea-card"><div class="idea-number">0${index + 1}</div><div class="idea-content"><p class="idea-meta">${escapeHtml(idea.audience)} · ${escapeHtml(idea.genre)}</p><h3>${escapeHtml(idea.title)}</h3><p>${escapeHtml(idea.premise)}</p><div class="idea-hook">${escapeHtml(idea.hook)}</div><details><summary>长期冲突与创作难点</summary><p>${escapeHtml(idea.longTermConflict)}</p><ul>${(idea.risks || []).map(risk => `<li>${escapeHtml(risk)}</li>`).join('')}</ul></details><div class="idea-actions"><button type="button" data-discuss-idea="${index}" class="secondary">讨论这个方向</button><button type="button" data-idea="${escapeHtml(report.id)}" data-index="${index}">用这个方案创建作品</button></div></div></article>`).join('')}</div>
    <section class="discussion"><h2>选题讨论</h2><div class="messages" aria-live="polite">${detail.messages.map(message => `<div class="message ${message.role === 'USER' ? 'user' : 'assistant'}"><div class="message-author">${message.role === 'USER' ? '你' : '选题 Agent'} <small>${escapeHtml(message.created_at)}</small></div><div class="message-body">${escapeHtml(message.content)}</div>${message.ideas ? `<details><summary>查看本轮三个方案</summary><pre>${escapeHtml(pretty(message.ideas))}</pre></details>` : ''}</div>`).join('') || '<p class="muted">在下方补充你的想法，开始这份报告的讨论。</p>'}</div>
    <div id="discussionTask">${detail.tasks.filter(task => !['DONE','CANCELLED'].includes(task.status)).map(task => researchTaskProgress(task)).join('')}</div>
    <form id="researchChatForm" data-id="${escapeHtml(report.id)}"><label for="researchMessage">补充想法，继续讨论</label><textarea id="researchMessage" name="message" required maxlength="4000" placeholder="例如：我喜欢第二个方向，但希望主角更主动，减少系统设定，增加职业细节。" ${busy ? 'disabled' : ''}></textarea><div class="composer-footer"><small>回复后会更新上方方案，交流记录持续保留。</small><button ${busy ? 'disabled' : ''}>发送并调整方案 →</button></div></form></section>
    <details class="report-sources"><summary>参考资料与最初想法</summary><p>${escapeHtml(report.brief)}</p>${JSON.parse(report.sources).length ? `<pre>${escapeHtml(pretty(report.sources))}</pre>` : '<p class="muted">本次基于个人想法进行创意讨论。</p>'}</details>`;
  const replies = detail.messages.filter(message => message.role !== 'USER');
  panel.querySelectorAll('.message.assistant').forEach((element,index) => {
    if (replies[index]?.task_id) element.insertAdjacentHTML('beforeend',`<button type="button" class="secondary message-trace" data-observe-task="${escapeHtml(replies[index].task_id)}">查看生成过程、耗时与费用</button>`);
  });
  const composer = panel.querySelector('#researchChatForm');
  composer.querySelector('.composer-footer').insertAdjacentHTML('beforebegin','<div id="researchChatModelFields" class="research-model-fields"></div>');
  await researchModelFields(document.querySelector('#researchChatModelFields'),state.researchChatChoices[report.id] || detail.modelChoice,
    value => {state.researchChatChoices[report.id] = value;},busy);
}
function researchTaskProgress(task) {
  return `<section class="task-progress" data-progress-task="${escapeHtml(task.id)}" aria-live="polite"><div class="task-progress-heading"><strong data-progress-stage>${escapeHtml(labels[task.status] || task.status)}</strong><span data-progress-elapsed></span></div><p data-progress-model class="muted">正在读取持久化执行记录…</p><p data-progress-error class="error">${escapeHtml(task.error || '')}</p><small>任务 ${escapeHtml(task.id)}</small><details><summary>最近执行事件 · 北京时间</summary><div data-progress-events></div></details><button type="button" class="secondary" data-observe-task="${escapeHtml(task.id)}">查看调用与执行详情</button>${task.status === 'ERROR' ? `<button type="button" data-retry="${escapeHtml(task.id)}">确认重新执行</button>` : ''}</section>`;
}
async function refreshResearchProgress() {
  await Promise.all([...content.querySelectorAll('[data-progress-task]')].map(async element => {
    try {
      const progress = await api(`/observability/tasks/${encodeURIComponent(element.dataset.progressTask)}/progress`);
      if (!element.isConnected) return;
      const task = progress.task;
      element.querySelector('[data-progress-stage]').textContent = progress.stage;
      element.querySelector('[data-progress-elapsed]').textContent = task.elapsed_ms == null ? `任务创建于 ${localTime(task.created_at)}` : `本次执行 ${duration(task.elapsed_ms)}`;
      element.querySelector('[data-progress-model]').textContent = task.call_id ? `${task.provider} · ${task.model} · 模型等待 ${duration(task.model_elapsed_ms)} · ${task.input_tokens == null ? '完整响应返回后记录 token 与费用' : `输入 / 输出 ${task.input_tokens} / ${task.output_tokens} token · 费用 ${money(task.actual)}`}` : '尚未发送模型请求；任务记录已保存在本地数据库。';
      element.querySelector('[data-progress-error]').textContent = task.error || '';
      element.querySelector('[data-progress-events]').innerHTML = progress.events.map(event => `<p><small>${escapeHtml(localTime(event.created_at))} · ${escapeHtml(event.type)}</small></p>`).join('') || '<p class="muted">暂无执行事件。</p>';
    } catch(error) {
      if (element.isConnected) element.querySelector('[data-progress-error]').textContent = `无法读取执行进度：${error.message}`;
    }
  }));
}
async function renderBudget() {
  const budget = await api('/budget');
  content.innerHTML = `<h1>费用与调用</h1><div class="stats"><div class="stat"><strong>¥${budget.limit}</strong><small>${escapeHtml(budget.month)} 总额度</small></div><div class="stat"><strong>¥${Number(budget.usedAndReserved).toFixed(4)}</strong><small>已结算与预留金额</small></div><div class="stat"><strong>¥${Number(budget.remaining).toFixed(4)}</strong><small>可用额度</small></div></div><p class="muted">费用依据你配置的人民币单价计算。结果不确定的调用保留预留金额；在供应商后台核对后，填写实际费用，再明确重新执行失败任务。</p><div class="card table-wrap"><table><thead><tr><th>时间 / 模型</th><th>状态</th><th>输入 / 输出 token</th><th>预留 / 实际费用</th><th>操作</th></tr></thead><tbody>${budget.calls.map(call => `<tr><td>${escapeHtml(call.created_at)}<br>${escapeHtml(call.model)}</td><td>${badge(call.status)}</td><td>${call.input_tokens ?? '—'} / ${call.output_tokens ?? '—'}</td><td>¥${call.reserved} / ${call.actual ?? '待确认'}</td><td>${call.status === 'UNCERTAIN' ? `<form class="reconcileForm" data-id="${escapeHtml(call.id)}"><input name="actual" type="number" min="0" max="10000" step="0.000001" required placeholder="实际费用"><button>确认费用</button></form>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
  content.querySelectorAll('tbody tr').forEach((row, index) => {
    const call = budget.calls[index];
    row.cells[0].insertAdjacentHTML('beforeend', `<br><small>${escapeHtml(call.provider)} · ${call.billing_mode === 'unmetered' ? '免费调用' : '按 token 计费'}</small>`);
  });
}
async function renderObservation(panel, novelId = '') {
  const filters = {...state.observation, novelId:novelId || state.observation.novelId};
  const query = new URLSearchParams(Object.entries(filters).filter(([,value]) => value !== ''));
  const [data, agents, novels] = await Promise.all([api(`/observability?${query}`),api('/agents'),api('/novels')]);
  if (panel !== content && (state.view !== 'novel' || state.tab !== 'observability') || panel === content && state.view !== 'observability') return;
  const total = key => data.summary.reduce((sum,row) => sum + Number(row[key] || 0),0);
  const name = kind => agents.find(agent => agent.kind === kind)?.agent || kind;
  const options = (entries, selected) => entries.map(([value,label]) => `<option value="${escapeHtml(value)}" ${String(value) === String(selected) ? 'selected' : ''}>${escapeHtml(label)}</option>`).join('');
  panel.innerHTML = `<div class="observation-head"><div><h1>Agent 观测看板</h1><p class="muted">按任务关联每次执行、调用费用与检查结果。数据持续保存在本地数据库。</p></div><span class="badge">每 5 秒更新</span></div>
    <form id="observationFilter" class="card observation-filter">
    ${novelId ? '' : `<label>作品<select name="novelId">${options([['','全部作品与选题'],...novels.map(item => [item.id,item.title])],filters.novelId)}</select></label>`}
    <label>章节<input name="chapter" type="number" min="0" step="1" placeholder="全部；0 为选题" value="${escapeHtml(filters.chapter)}"></label>
    <label>Agent<select name="kind">${options([['','全部 Agent'],...agents.map(agent => [agent.kind,`${agent.agent} · ${agent.kind}`])],filters.kind)}</select></label>
    <label>任务状态<select name="status">${options([['','全部状态'],...['WAITING','RUNNING','READY','DONE','ERROR','CANCELLED'].map(value => [value,labels[value]])],filters.status)}</select></label><button>应用筛选</button></form>
    <div class="stats observation-stats"><div class="stat"><strong>${money(total('settled_cost'))}</strong><small>累计结算费用 · ${total('model_calls')} 次调用</small></div><div class="stat"><strong>${money(total('pending_reservation'))}</strong><small>尚未结算的预留费用</small></div><div class="stat"><strong>${duration(total('execution_ms'))}</strong><small>累计完整执行耗时 · 并行任务分别累计</small></div><div class="stat"><strong>${total('input_tokens').toLocaleString()} / ${total('output_tokens').toLocaleString()}</strong><small>输入 / 输出 token</small></div></div>
    <section class="card table-wrap"><h2>各 Agent 的开销与效果</h2><p class="muted">检查通过率来自自动判断；人工评价按每个任务最新评分汇总。缓存 token 为供应商报告值，费用仍按已配置单价估算。</p>
    <table><thead><tr><th>Agent / 职责</th><th>完成 / 失败 / 执行中</th><th>模型调用 / token</th><th>结算 / 预留</th><th>执行平均 / 模型平均</th><th>检查通过 / 未通过</th><th>人工评价</th></tr></thead><tbody>${data.summary.map(row => {
      const agent = agents.find(item => item.kind === row.kind);
      return `<tr><td><strong>${escapeHtml(name(row.kind))}</strong><br><small>${escapeHtml(agent?.purpose || row.kind)}</small><br><small>${row.execution_count} 次执行 · ${row.interrupted_count} 次中断</small></td><td>${row.completed_tasks} / ${row.failed_tasks} / ${row.running_tasks}</td><td>${row.model_calls} 次<br>${row.input_tokens} / ${row.output_tokens}<br><small>缓存 ${row.cached_input_tokens ?? '未提供'} · ${row.cache_reported_calls} 次报告</small></td><td>${money(row.settled_cost)}<br><small>${money(row.pending_reservation)}</small></td><td>${duration(row.timed_executions ? row.execution_ms / row.timed_executions : null)}<br><small>模型 ${duration(row.timed_calls ? row.model_ms / row.timed_calls : null)}</small></td><td>${Number(row.passed_reviews) + Number(row.rejected_reviews) ? `${row.passed_reviews} / ${row.rejected_reviews}<br><small>${row.issue_count} 个问题 · 通过率 ${(100 * row.passed_reviews / (Number(row.passed_reviews) + Number(row.rejected_reviews))).toFixed(0)}%</small>` : '暂无检查结论'}</td><td>${row.rated_tasks ? `${Number(row.human_rating).toFixed(1)} / 5<br><small>${row.rated_tasks} 个任务</small>` : '尚无评价'}</td></tr>`;
    }).join('') || '<tr><td colspan="7" class="empty">当前筛选没有任务，生成内容后将在这里显示。</td></tr>'}</tbody></table></section>
    <section class="card table-wrap"><h2>任务执行记录 <small>共 ${data.total} 个</small></h2><table><thead><tr><th>任务 / 作品 / 章节</th><th>状态 / 模型</th><th>执行耗时 / 次数</th><th>输入 / 输出 token</th><th>结算 / 预留</th><th>结果</th></tr></thead><tbody>${data.tasks.map(task => `<tr><td><strong>${escapeHtml(name(task.kind))}</strong><br>${escapeHtml(task.novel_title || '选题研究')} · 第 ${task.chapter_no} 章 / 修改 ${task.revision} 轮<br><small>${escapeHtml(task.created_at)}</small></td><td>${badge(task.status)}<br><small>${escapeHtml(task.provider || '尚无调用')} ${escapeHtml(task.model || '')}</small>${task.call_status ? `<br><small>调用：${escapeHtml(labels[task.call_status] || task.call_status)}</small>` : ''}</td><td>${duration(task.execution_ms)}<br><small>${task.execution_count ?? 0} 次 · 模型 ${duration(task.latency_ms)}</small></td><td>${task.input_tokens ?? '—'} / ${task.output_tokens ?? '—'}<br><small>缓存 ${task.cached_input_tokens ?? '未提供'}</small></td><td>${task.call_id ? `${money(task.actual)}<br><small>预留 ${money(task.reserved)}</small>` : '尚无调用'}</td><td><button class="secondary" data-observe-task="${escapeHtml(task.id)}">查看过程与评价</button>${task.human_rating ? `<br><small>人工 ${task.human_rating} / 5</small>` : ''}</td></tr>`).join('') || '<tr><td colspan="6" class="empty">暂无任务记录</td></tr>'}</tbody></table><div class="observation-pagination"><button class="secondary" data-observation-page="${data.page - 1}" ${data.page === 0 ? 'disabled' : ''}>上一页</button><span>第 ${data.page + 1} / ${Math.max(1,Math.ceil(data.total / data.pageSize))} 页</span><button class="secondary" data-observation-page="${data.page + 1}" ${(data.page + 1) * data.pageSize >= data.total ? 'disabled' : ''}>下一页</button></div></section>
    <p class="muted">${escapeHtml(data.storage)}。${escapeHtml(data.historicalTiming)}。自动检查无法直接代表读者体验。</p>`;
}
async function showObservation(id) {
  const detail = await api(`/observability/tasks/${encodeURIComponent(id)}`);
  const task = detail.task;
  const callMetadata = detail.call && Object.fromEntries(Object.entries(detail.call).filter(([key]) => key !== 'response'));
  state.observationTaskId = id;
  document.querySelector('#observationTitle').textContent = `${task.kind} · 第 ${task.chapter_no} 章 · 修改 ${task.revision} 轮`;
  document.querySelector('#observationDetail').innerHTML = `<p>${badge(task.status)} <small>任务 ${escapeHtml(task.id)} · 记忆版本 ${task.memory_version}</small></p>
    ${task.error ? `<p class="error">${escapeHtml(task.error)}</p>` : ''}
    <h3>执行时间线</h3>${detail.executions.map(run => `<article class="execution-item"><strong>${escapeHtml(labels[run.status] || ({INTERRUPTED:'服务中断',RATE_WAIT:'等待调用额度',REJECTED:'线程未接受任务'})[run.status] || run.status)}</strong> · ${run.result_source === 'CHECKPOINT' ? '使用已存响应' : '生成内容'}<p><small>开始 ${escapeHtml(run.started_at)} · 结束 ${escapeHtml(run.finished_at || '未知 / 尚未结束')}<br>排队 ${duration(run.queue_ms)} · 执行 ${duration(run.duration_ms)}</small></p>${run.error ? `<p class="error">${escapeHtml(run.error)}</p>` : ''}${run.approved == null ? '' : `<p>自动检查：${run.approved ? '通过' : '未通过'} · 问题 ${run.issue_count ?? 0} 个 · ${run.major_change ? '涉及重大变更' : '常规检查'}</p>`}${run.body_characters == null ? '' : `<p>非空白字符：${run.body_characters}</p>`}${run.fact_count == null ? '' : `<p>提取事实 ${run.fact_count} 条 · 关系 ${run.relation_count ?? 0} 条</p>`}${run.result ? `<details><summary>本次执行输出</summary><pre>${escapeHtml(pretty(run.result))}</pre></details>` : ''}</article>`).join('') || '<p class="muted">该任务没有新增观测记录，已有结果仍可在下方查看。</p>'}
    <h3>模型调用</h3>${callMetadata ? `<pre>${escapeHtml(pretty(callMetadata))}</pre>` : '<p class="muted">尚无模型调用记录。</p>'}
    <details><summary>任务输入与最终结果</summary><pre>${escapeHtml(pretty(task.payload))}</pre>${task.result ? `<pre>${escapeHtml(pretty(task.result))}</pre>` : '<p>尚无通过结构检查的输出</p>'}</details>
    <details><summary>关联事件（最近 500 条）</summary>${detail.events.map(event => `<p><small>${escapeHtml(event.created_at)} · ${escapeHtml(event.type)}</small><br>${escapeHtml(event.message)}</p>`).join('') || '<p>暂无事件</p>'}</details>
    <h3>人工效果评价</h3><p class="muted">可以评价正文可读性，或检查意见是否准确。每次评价独立保存。</p>${task.result ? `<form id="observationFeedback" data-id="${escapeHtml(task.id)}"><label>评分<select name="rating">${[1,2,3,4,5].map(rating => `<option value="${rating}" ${rating === 3 ? 'selected' : ''}>${rating} / 5</option>`).join('')}</select></label><label>评价依据<textarea name="note" maxlength="4000" required placeholder="例如：指出的人物知情矛盾准确，但节奏建议缺少依据。"></textarea></label><button>保存评价</button></form>` : '<p>任务产生有效输出后可以评价。</p>'}${detail.feedback.map(item => `<article class="execution-item"><strong>${item.rating} / 5</strong> <small>${escapeHtml(item.created_at)}</small><p class="feedback-note">${escapeHtml(item.note)}</p></article>`).join('')}`;
  if (detail.call?.response) {
    const raw = document.createElement('details');
    const summary = document.createElement('summary');summary.textContent = '模型原始输出';
    const pre = document.createElement('pre');pre.textContent = detail.call.response;
    raw.append(summary,pre);
    document.querySelector('#observationDetail').append(raw);
  }
  const dialog = document.querySelector('#observationDialog');
  if (!dialog.open) dialog.showModal();
}
function taskCard(task) {
  return `<div class="card"><h3>${escapeHtml(task.kind)} ${badge(task.status)} <small>第${task.chapter_no}章 · 修改${task.revision}轮</small></h3>${task.error ? `<p class="error">${escapeHtml(task.error)}</p>` : ''}${task.status === 'ERROR' ? `<button data-retry="${escapeHtml(task.id)}">确认重新执行</button>` : ''}<details><summary>输入、输出与记忆版本 ${task.memory_version}</summary><pre>${escapeHtml(pretty(task.payload))}</pre>${task.result ? `<pre>${escapeHtml(pretty(task.result))}</pre>` : ''}</details></div>`;
}
async function renderQuality(panel) {
  const quality = await api(`/novels/${state.id}/quality`);
  panel.innerHTML = `<div class="stats"><div class="stat"><strong>${quality.totals.chapters}</strong><small>已通过检查的章节</small></div><div class="stat"><strong>${quality.totals.stored_characters}</strong><small>正文字符数（含空白）</small></div><div class="stat"><strong>${quality.dialogueRising ? '需要关注' : '持续记录'}</strong><small>最近三章对话占比趋势</small></div></div><p class="muted">对话占比按含引号的非空行数计算。节奏标记来自检查报告，可结合正文阅读评价。</p><div class="card table-wrap"><table><thead><tr><th>章节</th><th>非空白字符</th><th>对话行 / 全部非空行</th><th>对话占比</th><th>叙事结构</th></tr></thead><tbody>${quality.recent.map(item => `<tr><td>第${item.chapter_no}章</td><td>${item.characters}</td><td>${item.dialogue_lines} / ${item.total_lines}</td><td>${(item.dialogue_ratio * 100).toFixed(1)}%</td><td>${item.pacing_data ? `<details><summary>${escapeHtml(JSON.parse(item.pacing_data).pattern)}</summary><pre>${escapeHtml(pretty(item.pacing_data))}</pre></details>` : '尚无报告'}</td></tr>`).join('')}</tbody></table></div>`;
}
async function renderNovel() {
  const detail = await api(`/novels/${state.id}`); state.detail = detail; const novel = detail.novel;
  content.innerHTML = `<h1>${escapeHtml(novel.title)}</h1><p>${badge(novel.status)} <span class="muted">第${novel.stage_no}阶段 · 当前第${novel.chapter_no}章 · 记忆版本 ${novel.memory_version}</span></p>${novel.error ? `<p class="error">${escapeHtml(novel.error)}</p>` : ''}<div class="actions">${['PLANNING','WRITING','CHECKING','REFLECTING','EXTRACTING','CHECKING_MEMORY'].includes(novel.status) ? '<button data-operation="pause" class="secondary">暂停创作</button>' : ''}${novel.status === 'PAUSED' ? '<button data-operation="resume">恢复创作</button>' : ''}${novel.status === 'AWAITING_STAGE_REVIEW' ? '<button data-operation="continue">通过阶段审核，规划下一阶段</button>' : ''}<a href="/api/novels/${escapeHtml(novel.id)}/export">导出已通过检查的正文</a></div><div class="tabs">${[['overview','概况'],['plan','阶段大纲'],['chapters','章节'],['memory','人物与记忆'],['quality','质量记录'],['truth','交接资料'],['tasks','执行记录'],['edits','历史修改']].map(([tab,title]) => `<button data-tab="${tab}" class="${state.tab === tab ? 'active' : ''}">${title}</button>`).join('')}</div><div id="novelContent"></div>`;
  const panel = document.querySelector('#novelContent');
  content.querySelector('.tabs').insertAdjacentHTML('beforeend', `<button data-tab="observability" class="${state.tab === 'observability' ? 'active' : ''}">Agent 看板</button>`);
  if (state.tab === 'observability') await renderObservation(panel,state.id);
  if (state.tab === 'overview') panel.innerHTML = `<div class="card"><h2>创作设定</h2><p>${escapeHtml(novel.premise)}</p><pre>${escapeHtml(novel.bible || '世界设定由阶段大纲与正文逐步建立。')}</pre></div>${novel.reflection ? `<div class="card pending"><h2>检查与修改计划</h2><pre>${escapeHtml(pretty(novel.reflection))}</pre></div>` : ''}${['AWAITING_CHANGE','AWAITING_HELP'].includes(novel.status) ? `<form id="reviseForm" class="card"><h2>人工修改要求</h2><label>批准的修改要求</label><textarea name="instructions" required maxlength="12000"></textarea><label>完整阶段大纲（重大变更必填）</label><textarea name="plan" class="code">${escapeHtml(novel.plan ? pretty(novel.plan) : '')}</textarea><button>批准修改并重新检查</button></form>` : ''}<div class="card"><h2>最近事件</h2>${detail.events.slice(0,12).map(event => `<p><small>${escapeHtml(event.created_at)} · ${escapeHtml(event.type)}</small><br>${escapeHtml(event.message)}</p>`).join('')}</div>`;
  if (state.tab === 'plan') panel.innerHTML = novel.plan ? `<form id="planForm" class="card"><h2>阶段大纲</h2><p class="muted">审核阶段目标、核心剧情节点、人物变化和伏笔安排。可以修改 JSON 后批准。</p><textarea name="plan" class="code" ${novel.status === 'AWAITING_PLAN' ? '' : 'readonly'}>${escapeHtml(pretty(novel.plan))}</textarea>${novel.status === 'AWAITING_PLAN' ? '<button>批准大纲并开始创作</button>' : ''}</form>` : '<p class="empty">大纲尚在规划中，请查看执行记录。</p>';
  if (state.tab === 'chapters') panel.innerHTML = `<div class="card table-wrap"><table><thead><tr><th>章节</th><th>状态</th><th>修改轮次</th><th>发布标记</th><th>操作</th></tr></thead><tbody>${detail.chapters.map(chapter => `<tr><td>第${chapter.number}章 ${escapeHtml(chapter.title)}</td><td>${badge(chapter.status)}</td><td>${chapter.revision}</td><td>${chapter.published ? '已发布' : '未发布'}</td><td><button data-read="${chapter.number}" class="secondary">阅读</button> ${chapter.status === 'ACCEPTED' ? `<button data-published="${chapter.number}" data-value="${!chapter.published}" class="secondary">${chapter.published ? '取消发布标记' : '标记已发布'}</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
  if (state.tab === 'tasks') panel.innerHTML = detail.tasks.map(taskCard).join('');
  if (state.tab === 'overview') {
    panel.insertAdjacentHTML('afterbegin', `<form id="modelForm" class="card"><h2>模型选择</h2><p>当前服务：${escapeHtml(novel.provider)} · 模型：${escapeHtml(novel.model_name || '服务默认')}</p><div id="novelModelFields"></div><button ${['PAUSED','AWAITING_PLAN','AWAITING_STAGE_REVIEW','AWAITING_CHANGE','AWAITING_HELP','ERROR','BUDGET_PAUSED'].includes(novel.status) && !detail.tasks.some(task => task.status === 'RUNNING') ? '' : 'disabled'}>保存模型选择</button><p class="muted">创作期间可暂停并等待当前调用结束后切换模型。</p></form>`);
    await modelFields(document.querySelector('#novelModelFields'), novel.provider, novel.model_name);
  }
  if (state.tab === 'quality') await renderQuality(panel);
  if (state.tab === 'truth') {
    const files = await api(`/novels/${state.id}/truth-files`);
    panel.innerHTML = `<div class="card"><h2>当前交接资料</h2><p class="muted">根据已保存的正文、人物关系、伏笔与质量记录生成。通过检查的新章节会更新这些资料。</p><a href="/api/novels/${escapeHtml(state.id)}/truth-files/export">下载全部交接资料 ZIP</a>${Object.entries(files).map(([name,text]) => `<details><summary>${escapeHtml(name)}</summary><pre>${escapeHtml(text)}</pre></details>`).join('')}</div>`;
  }
  if (state.tab === 'memory') {
    const [graph, memories] = await Promise.all([api(`/novels/${state.id}/graph`),api(`/novels/${state.id}/memories`)]);
    panel.innerHTML = `<form id="graphForm" class="card"><h2>人物关系查询</h2><label>人物或秘密名称，使用英文逗号分隔；留空查看全部关系</label><input name="characters"><label>关联查询深度</label><select name="depth"><option>1</option><option selected>2</option><option>3</option></select><button>查询 graph</button><div id="graphResult">${graphTable(graph)}</div></form><div class="card"><h2>当前创作记忆</h2>${memories.map(item => `<details><summary>${escapeHtml(item.kind)} · ${escapeHtml(item.memory_key)} · 第${item.chapter_no}章</summary><pre>${escapeHtml(pretty(item.content))}</pre></details>`).join('') || '<p class="muted">章节通过正文与记忆检查后，变更会统一保存。</p>'}</div>`;
  }
  if (state.tab === 'edits') panel.innerHTML = `<form id="editForm" class="card"><h2>历史章节修改</h2><p class="muted">暂停创作并等待执行中的任务结束后，可以分析已通过检查的章节。确认影响范围后，系统重新规划受影响章节。</p><label>章节编号</label><input name="chapter" type="number" min="1" required><label>修改要求</label><textarea name="instructions" required maxlength="12000"></textarea><button>分析影响范围</button></form>${detail.edits.map(edit => `<div class="card"><h3>第${edit.chapter_no}章 · ${escapeHtml(edit.status)}</h3><p>${escapeHtml(edit.instruction)}</p><pre>${escapeHtml(pretty(edit.impact))}</pre>${edit.status === 'PENDING' ? `<form class="approveEditForm" data-id="${escapeHtml(edit.id)}"><label class="inline-check"><input name="acknowledgePublished" type="checkbox">确认涉及已发布章节时，由我处理平台上的正文修改</label><button>批准影响范围并重新生成</button></form>` : ''}</div>`).join('')}`;
}
function graphTable(graph) {
  return `<p class="muted">版本 ${graph.version} · ${graph.nodes.length}个人物或信息节点 · ${graph.edges.length}条关系</p><div class="table-wrap"><table><thead><tr><th>主体</th><th>关系</th><th>对象</th><th>来源章节 / 证据</th></tr></thead><tbody>${graph.edges.map(edge => `<tr><td>${escapeHtml(edge.subject)}</td><td>${escapeHtml(edge.predicate)}</td><td>${escapeHtml(edge.object)}</td><td>第${edge.chapter}章<br>${escapeHtml(edge.evidence)}</td></tr>`).join('')}</tbody></table></div>`;
}
document.addEventListener('click', async event => {
  const button = event.target.closest('button'); if (!button || button.type === 'submit' && button.closest('form')) return;
  try {
    if (button.id === 'closeObservation') document.querySelector('#observationDialog').close();
    if (button.dataset.observeTask) await showObservation(button.dataset.observeTask);
    if (button.dataset.observationPage !== undefined) {
      state.observation.page = Number(button.dataset.observationPage);
      await renderObservation(state.view === 'novel' ? document.querySelector('#novelContent') : content,state.view === 'novel' ? state.id : '');
    }
    if (button.dataset.view) { state.view = button.dataset.view; state.id = null; await render(); }
    if (button.dataset.novel) { state.view = 'novel'; state.id = button.dataset.novel; state.tab = 'overview'; state.observation.page = 0; await render(); }
    if (button.dataset.tab) { state.tab = button.dataset.tab; await renderNovel(); }
    if (button.dataset.report) { state.researchId = button.dataset.report; await renderResearch(); }
    if (button.dataset.discussIdea !== undefined) { const input = document.querySelector('#researchMessage'); if (!input.disabled) { input.value = `关于第${Number(button.dataset.discussIdea) + 1}个方案，我想进一步讨论：`;input.focus();input.scrollIntoView({behavior:'smooth',block:'center'}); } }
    if (button.dataset.operation) { await api(`/novels/${state.id}/${button.dataset.operation}`,'POST'); notice('操作已完成'); await render(); }
    if (button.dataset.retry) { await api(`/tasks/${button.dataset.retry}/retry`,'POST'); notice('已明确重新执行该任务'); await render(); }
    if (button.dataset.idea) { state.idea = JSON.parse(state.researches.find(item => item.id === button.dataset.idea).ideas)[Number(button.dataset.index)]; state.view = 'new'; await render(); }
    if (button.dataset.read) { const chapter = await api(`/novels/${state.id}/chapters/${button.dataset.read}`); document.querySelector('#readerTitle').textContent = `第${chapter.number}章 ${chapter.title}`; document.querySelector('#readerBody').textContent = chapter.body; document.querySelector('#reader').showModal(); }
    if (button.dataset.published) { await api(`/novels/${state.id}/chapters/${button.dataset.published}/published`,'POST',{published:button.dataset.value === 'true'}); await renderNovel(); }
    if (button.id === 'refreshButton') await render();
    if (button.id === 'closeReader') document.querySelector('#reader').close();
  } catch(error) { notice(error.message,true); }
});
document.addEventListener('submit', async event => {
  event.preventDefault(); const form = event.target; const data = Object.fromEntries(new FormData(form)); const button = form.querySelector('button'); button.disabled = true;
  try {
    if (form.id === 'observationFilter') {
      state.observation = {...state.observation,...data,page:0};
      await renderObservation(state.view === 'novel' ? document.querySelector('#novelContent') : content,state.view === 'novel' ? state.id : '');
      return;
    }
    if (form.id === 'observationFeedback') {
      await api(`/observability/tasks/${encodeURIComponent(form.dataset.id)}/feedback`,'POST',{rating:Number(data.rating),note:data.note});
      notice('评价已保存到本地数据库');
      await showObservation(form.dataset.id);
      return;
    }
    if (form.id === 'newForm') { const created = await api('/novels','POST',{...data,stageSize:Number(data.stageSize),novelBudget:Number(data.novelBudget),chapterBudget:Number(data.chapterBudget)}); state.view = 'novel'; state.id = created.id; state.tab = 'overview';state.idea = null; }
    if (form.id === 'researchForm') { await api('/research','POST',{brief:data.brief.trim(),urls:data.urls.split(/\r?\n/).map(item => item.trim()).filter(Boolean),provider:data.provider,model:data.model || ''});state.researchId = null; }
    if (form.id === 'researchChatForm') await api(`/research/${form.dataset.id}/messages`,'POST',{message:data.message,provider:data.provider,model:data.model || ''});
    if (form.id === 'modelForm') await api(`/novels/${state.id}/model`,'POST',data);
    if (form.id === 'planForm') await api(`/novels/${state.id}/approve-plan`,'POST',JSON.parse(data.plan));
    if (form.id === 'reviseForm') await api(`/novels/${state.id}/revise`,'POST',{instructions:data.instructions,plan:data.plan.trim() ? JSON.parse(data.plan) : null});
    if (form.id === 'editForm') await api(`/novels/${state.id}/edits`,'POST',{chapter:Number(data.chapter),instructions:data.instructions});
    if (form.classList.contains('approveEditForm')) await api(`/edits/${form.dataset.id}/approve`,'POST',{acknowledgePublished:data.acknowledgePublished === 'on'});
    if (form.classList.contains('reconcileForm')) await api(`/calls/${form.dataset.id}/reconcile`,'POST',{actual:Number(data.actual)});
    if (form.id === 'graphForm') { const graph = await api(`/novels/${state.id}/graph?characters=${encodeURIComponent(data.characters)}&depth=${data.depth}`); document.querySelector('#graphResult').innerHTML = graphTable(graph);button.disabled = false;return; }
    notice('操作已保存'); await render();
  } catch(error) { notice(error.message,true);button.disabled = false; }
});
render().catch(error => notice(error.message,true));
setInterval(async () => {
  try {
    await sidebar();
    if (state.view === 'novel' && state.tab === 'tasks') await renderNovel();
    if ((state.view === 'observability' || state.view === 'novel' && state.tab === 'observability') && !state.observationRefreshing && !document.querySelector('#observationDialog').open && !document.activeElement?.closest('#observationFilter')) {
      state.observationRefreshing = true;
      try { await renderObservation(state.view === 'novel' ? document.querySelector('#novelContent') : content,state.view === 'novel' ? state.id : ''); }
      finally { state.observationRefreshing = false; }
    }
    if (state.view === 'research' && !state.researchRefreshing) {
      const tasks = await api('/research/tasks');
      const signature = JSON.stringify(tasks.map(task => [task.id,task.status]));
      if (state.view !== 'research') return;
      if (signature === state.researchSignature) { await refreshResearchProgress();return; }
      if (document.activeElement?.matches('#researchModelFields select,#researchChatModelFields select')) { await refreshResearchProgress();return; }
      state.researchRefreshing = true;
      const brief = document.querySelector('#researchBrief')?.value || '';
      const urls = document.querySelector('#researchUrls')?.value || '';
      const message = document.querySelector('#researchMessage')?.value || '';
      const reportId = state.researchId;
      const scroll = window.scrollY;
      const sourceOpen = document.querySelector('.source-input')?.open;
      const focused = document.activeElement;
      const focusId = ['researchBrief','researchUrls','researchMessage'].includes(focused?.id) ? focused.id : null;
      const selection = focusId ? [focused.selectionStart,focused.selectionEnd] : null;
      try {
        await renderResearch();
        if (state.view !== 'research') return;
        document.querySelector('#researchBrief').value = brief;
        document.querySelector('#researchUrls').value = urls;
        document.querySelector('.source-input').open = sourceOpen;
        if (reportId === state.researchId && document.querySelector('#researchMessage')) document.querySelector('#researchMessage').value = message;
        const input = focusId ? document.getElementById(focusId) : null;
        if (input && !input.disabled) { input.focus({preventScroll:true});input.setSelectionRange(...selection); }
        window.scrollTo(0,scroll);
      } finally { state.researchRefreshing = false; }
    }
  } catch(error) { notice(error.message,true); }
},5000);
