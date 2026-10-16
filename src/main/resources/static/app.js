const state = {view: 'new', id: null, tab: 'overview', detail: null, idea: null};
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
  document.querySelector('#engineStatus').textContent = `${status.engine.active}/${status.engine.workers} 个任务执行中 · ${status.modelConfigured ? '模型已配置' : '模型尚未配置'}`;
  document.querySelector('#budgetStatus').textContent = `${budget.month} · 已用及预留 ¥${Number(budget.usedAndReserved).toFixed(2)} / ¥${budget.limit}`;
}
async function render() {
  document.querySelector('#breadcrumb').textContent = {new:'创建作品',research:'市场资料与原创选题',budget:'费用与调用记录',novel:'作品管理'}[state.view];
  if (state.view === 'new') renderNew();
  if (state.view === 'research') await renderResearch();
  if (state.view === 'budget') await renderBudget();
  if (state.view === 'novel') await renderNovel();
  await sidebar();
}
function renderNew() {
  content.innerHTML = `<div class="intro"><h1>让故事持续生长</h1><p class="muted">选定创意，审核阶段大纲。写作、并行检查与修改由 Agent 持续执行，重要剧情变化交给你决定。</p></div>
  <form id="newForm" class="card compact"><h2>创建连载小说</h2><label>作品名称</label><input name="title" required maxlength="200" value="${escapeHtml(state.idea?.title || '')}"><label>故事创意与读者期待</label><textarea name="premise" required maxlength="12000">${escapeHtml(state.idea ? JSON.stringify(state.idea,null,2) : '')}</textarea><label>创作设定与限制</label><textarea name="bible" maxlength="12000" placeholder="世界规则、文风要求、需要避免的内容；可以留空，由阶段规划提出设定供审核。"></textarea>
  <div class="grid"><div><label>每阶段章节数量</label><input name="stageSize" type="number" min="1" max="20" value="10" required></div><div><label>本作品每月额度 / 元</label><input name="novelBudget" type="number" min="0.01" max="300" step="0.01" value="100" required></div><div><label>每章全部调用费用上限 / 元</label><input name="chapterBudget" type="number" min="0.01" max="100" step="0.01" value="5" required></div></div>
  <button>创建并规划</button></form>
  <details class="card compact"><summary>配置云端模型</summary><p>将 config/application-local.example.yml 复制为 config/application-local.yml，填写完整的 HTTPS Chat Completions 地址、API key、模型名称及人民币 token 单价，然后重新启动服务。</p><p>也可以通过 NOVEL_MODEL_ENDPOINT、NOVEL_MODEL_API_KEY、NOVEL_MODEL_NAME、NOVEL_INPUT_PRICE、NOVEL_OUTPUT_PRICE 环境变量配置。模型需要支持 JSON object 和 token 用量返回。密钥文件已加入 Git 忽略规则。</p></details>`;
}
async function renderResearch() {
  const [researches, tasks] = await Promise.all([api('/research'),api('/research/tasks')]);
  content.innerHTML = `<div class="intro"><h1>从资料发现故事</h1><p class="muted">读取番茄公开页面，分别考察男频和女频，提出三个原创创意。保存资料来源与采集时间，选题由你决定。</p></div><form id="researchForm" class="card"><label>公开榜单或作品详情地址，每行一个，最多五个</label><textarea name="urls" required placeholder="https://fanqienovel.com/…"></textarea><button>采集资料并生成创意</button></form>
  ${tasks.filter(task => task.status !== 'DONE').map(task => taskCard(task)).join('')}
  ${researches.map(research => `<section class="card"><h2>选题报告 <small>${escapeHtml(research.created_at)}</small></h2><div class="grid">${JSON.parse(research.ideas).map((idea,index) => `<div class="card"><h3>${escapeHtml(idea.title)}</h3><p>${escapeHtml(idea.audience)} · ${escapeHtml(idea.genre)}</p><p>${escapeHtml(idea.premise)}</p><p class="muted">${escapeHtml(idea.hook)}</p><details><summary>长期冲突、资料与难点</summary><pre>${escapeHtml(pretty(idea))}</pre></details><button data-idea="${escapeHtml(research.id)}" data-index="${index}">选择这个创意</button></div>`).join('')}</div><details><summary>采集资料</summary><pre>${escapeHtml(pretty(research.sources))}</pre></details></section>`).join('')}`;
  state.researches = researches;
}
async function renderBudget() {
  const budget = await api('/budget');
  content.innerHTML = `<h1>费用与调用</h1><div class="stats"><div class="stat"><strong>¥${budget.limit}</strong><small>${escapeHtml(budget.month)} 总额度</small></div><div class="stat"><strong>¥${Number(budget.usedAndReserved).toFixed(4)}</strong><small>已结算与预留金额</small></div><div class="stat"><strong>¥${Number(budget.remaining).toFixed(4)}</strong><small>可用额度</small></div></div><p class="muted">费用依据你配置的人民币单价计算。结果不确定的调用保留预留金额；在供应商后台核对后，填写实际费用，再明确重新执行失败任务。</p><div class="card table-wrap"><table><thead><tr><th>时间 / 模型</th><th>状态</th><th>输入 / 输出 token</th><th>预留 / 实际费用</th><th>操作</th></tr></thead><tbody>${budget.calls.map(call => `<tr><td>${escapeHtml(call.created_at)}<br>${escapeHtml(call.model)}</td><td>${badge(call.status)}</td><td>${call.input_tokens ?? '—'} / ${call.output_tokens ?? '—'}</td><td>¥${call.reserved} / ${call.actual ?? '待确认'}</td><td>${call.status === 'UNCERTAIN' ? `<form class="reconcileForm" data-id="${escapeHtml(call.id)}"><input name="actual" type="number" min="0" max="10000" step="0.000001" required placeholder="实际费用"><button>确认费用</button></form>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
}
function taskCard(task) {
  return `<div class="card"><h3>${escapeHtml(task.kind)} ${badge(task.status)} <small>第${task.chapter_no}章 · 修改${task.revision}轮</small></h3>${task.error ? `<p class="error">${escapeHtml(task.error)}</p>` : ''}${task.status === 'ERROR' ? `<button data-retry="${escapeHtml(task.id)}">确认重新执行</button>` : ''}<details><summary>输入、输出与记忆版本 ${task.memory_version}</summary><pre>${escapeHtml(pretty(task.payload))}</pre>${task.result ? `<pre>${escapeHtml(pretty(task.result))}</pre>` : ''}</details></div>`;
}
async function renderNovel() {
  const detail = await api(`/novels/${state.id}`); state.detail = detail; const novel = detail.novel;
  content.innerHTML = `<h1>${escapeHtml(novel.title)}</h1><p>${badge(novel.status)} <span class="muted">第${novel.stage_no}阶段 · 当前第${novel.chapter_no}章 · 记忆版本 ${novel.memory_version}</span></p>${novel.error ? `<p class="error">${escapeHtml(novel.error)}</p>` : ''}<div class="actions">${['PLANNING','WRITING','CHECKING','REFLECTING','EXTRACTING','CHECKING_MEMORY'].includes(novel.status) ? '<button data-operation="pause" class="secondary">暂停创作</button>' : ''}${novel.status === 'PAUSED' ? '<button data-operation="resume">恢复创作</button>' : ''}${novel.status === 'AWAITING_STAGE_REVIEW' ? '<button data-operation="continue">通过阶段审核，规划下一阶段</button>' : ''}<a href="/api/novels/${escapeHtml(novel.id)}/export">导出已通过检查的正文</a></div><div class="tabs">${[['overview','概况'],['plan','阶段大纲'],['chapters','章节'],['memory','人物与记忆'],['tasks','执行记录'],['edits','历史修改']].map(([tab,title]) => `<button data-tab="${tab}" class="${state.tab === tab ? 'active' : ''}">${title}</button>`).join('')}</div><div id="novelContent"></div>`;
  const panel = document.querySelector('#novelContent');
  if (state.tab === 'overview') panel.innerHTML = `<div class="card"><h2>创作设定</h2><p>${escapeHtml(novel.premise)}</p><pre>${escapeHtml(novel.bible || '世界设定由阶段大纲与正文逐步建立。')}</pre></div>${novel.reflection ? `<div class="card pending"><h2>检查与修改计划</h2><pre>${escapeHtml(pretty(novel.reflection))}</pre></div>` : ''}${['AWAITING_CHANGE','AWAITING_HELP'].includes(novel.status) ? `<form id="reviseForm" class="card"><h2>人工修改要求</h2><label>批准的修改要求</label><textarea name="instructions" required maxlength="12000"></textarea><label>完整阶段大纲（重大变更必填）</label><textarea name="plan" class="code">${escapeHtml(novel.plan ? pretty(novel.plan) : '')}</textarea><button>批准修改并重新检查</button></form>` : ''}<div class="card"><h2>最近事件</h2>${detail.events.slice(0,12).map(event => `<p><small>${escapeHtml(event.created_at)} · ${escapeHtml(event.type)}</small><br>${escapeHtml(event.message)}</p>`).join('')}</div>`;
  if (state.tab === 'plan') panel.innerHTML = novel.plan ? `<form id="planForm" class="card"><h2>阶段大纲</h2><p class="muted">审核阶段目标、核心剧情节点、人物变化和伏笔安排。可以修改 JSON 后批准。</p><textarea name="plan" class="code" ${novel.status === 'AWAITING_PLAN' ? '' : 'readonly'}>${escapeHtml(pretty(novel.plan))}</textarea>${novel.status === 'AWAITING_PLAN' ? '<button>批准大纲并开始创作</button>' : ''}</form>` : '<p class="empty">大纲尚在规划中，请查看执行记录。</p>';
  if (state.tab === 'chapters') panel.innerHTML = `<div class="card table-wrap"><table><thead><tr><th>章节</th><th>状态</th><th>修改轮次</th><th>发布标记</th><th>操作</th></tr></thead><tbody>${detail.chapters.map(chapter => `<tr><td>第${chapter.number}章 ${escapeHtml(chapter.title)}</td><td>${badge(chapter.status)}</td><td>${chapter.revision}</td><td>${chapter.published ? '已发布' : '未发布'}</td><td><button data-read="${chapter.number}" class="secondary">阅读</button> ${chapter.status === 'ACCEPTED' ? `<button data-published="${chapter.number}" data-value="${!chapter.published}" class="secondary">${chapter.published ? '取消发布标记' : '标记已发布'}</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
  if (state.tab === 'tasks') panel.innerHTML = detail.tasks.map(taskCard).join('');
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
    if (button.dataset.view) { state.view = button.dataset.view; state.id = null; await render(); }
    if (button.dataset.novel) { state.view = 'novel'; state.id = button.dataset.novel; state.tab = 'overview'; await render(); }
    if (button.dataset.tab) { state.tab = button.dataset.tab; await renderNovel(); }
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
    if (form.id === 'newForm') { const created = await api('/novels','POST',{...data,stageSize:Number(data.stageSize),novelBudget:Number(data.novelBudget),chapterBudget:Number(data.chapterBudget)}); state.view = 'novel'; state.id = created.id; state.tab = 'overview';state.idea = null; }
    if (form.id === 'researchForm') await api('/research','POST',{urls:data.urls.split(/\r?\n/).map(item => item.trim()).filter(Boolean)});
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
setInterval(async () => { try { await sidebar(); if (state.view === 'novel' && state.tab === 'tasks') await renderNovel(); } catch(error) { notice(error.message,true); } },5000);
