/* ============================================================
   综测材料整理 — 页面逻辑
   职责划分：后端算分（纯函数），前端只负责收集与呈现。
   任何分数都由 /api/score 返回，页面不自己算。
   ============================================================ */

// 字段顺序即展示顺序。第三个参数 = 只读（布尔值改成文本可编辑容易把类型搞坏）
const FIELDS = [
    ['doc_type', '材料类型', false],
    ['person_name', '姓名', false],
    ['is_multi_person', '含他人姓名', true],
    ['issuer', '颁发单位', false],
    ['date', '签发日期', false],
    ['period_start', '任期起', false],
    ['period_end', '任期止', false],
    ['title', '名称', false],
    ['achievement', '获奖等级', false],
    ['level_hint', '级别（弱判断）', false],
    ['category_guess', '类别（弱判断）', false],
    ['evidence_quote', '原文摘录', false],
    ['confidence', '置信度', false]
];

// 厂商默认值，与后端 ProviderDefaults 保持一致（页面选厂商时自动带出，仅是预填，
// 模型名可改成任意「支持图像输入」的模型，不写死）
const PROVIDER_DEFAULTS = {
    deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-flash' },
    doubao:   { baseUrl: 'https://ark.cn-beijing.volces.com/api/v1', model: 'doubao-vision-pro-32k' },
    openai:   { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
    claude:   { baseUrl: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-3.5-sonnet' },
    custom:   { baseUrl: '', model: '' }
};

const $ = id => document.getElementById(id);
$('port').textContent = location.port || '80';

function esc(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function setMsg(el, cls, text) {
    el.className = 'msg ' + cls;
    el.textContent = text;
}

function fmtSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

// ── 全局状态 ────────────────────────────────────────────────────────
const state = {
    profile: { name: '', studentId: '', className: '', college: '', grade: '' },
    items: [],          // {key, file, status, error, data, open}
    seq: 0,
    running: false,
    resolved: new Set() // `${key}:${page}:${field}` —— 用户手动确认过的字段
};

let exported = false;   // 是否成功导出过一次（用于步骤勾选）

// ══════════════════════════════════════════════════════════════════
//  步骤导航：从 .sec 生成，滚动时高亮，按状态勾选
// ══════════════════════════════════════════════════════════════════
const SECS = Array.from(document.querySelectorAll('.sec'));

function initNav() {
    $('steps').innerHTML = SECS.map(s =>
        '<a class="step" href="#' + s.id + '" data-target="' + s.id + '">'
        + '<span class="sn">' + esc(s.dataset.no) + '</span>'
        + '<span class="st">' + esc(s.dataset.name) + '</span>'
        + '<span class="sd"></span></a>'
    ).join('');

    $('steps').addEventListener('click', e => {
        const a = e.target.closest('.step');
        if (!a) return;
        e.preventDefault();
        const el = document.getElementById(a.dataset.target);
        if (el && el.style.display !== 'none') {
            el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    });
}

function setActiveSec(id) {
    document.querySelectorAll('.step').forEach(a =>
        a.classList.toggle('active', a.dataset.target === id));
}

let spyQueued = false;
function spyScroll() {
    if (spyQueued) return;
    spyQueued = true;
    requestAnimationFrame(() => {
        spyQueued = false;
        const visible = SECS.filter(s => s.style.display !== 'none');
        let cur = visible[0] || null;
        visible.forEach(s => {
            if (s.getBoundingClientRect().top <= 140) cur = s;
        });

        // 滚到底时最后一块常常到不了 140px 阈值，补点亮一次。
        // 但必须确认它真的进了视口——页面内容短的时候「到底」很容易发生，
        // 无条件点亮会让点「算分」跳到「导出」，高亮跟内容对不上。
        const atBottom = window.innerHeight + window.scrollY
                       >= document.documentElement.scrollHeight - 8;
        const last = visible[visible.length - 1];
        if (atBottom && last && last.getBoundingClientRect().top < window.innerHeight * 0.55) {
            cur = last;
        }
        setActiveSec(cur ? cur.id : null);
    });
}

/** 各步骤的完成状态：只表达「做完了没有」，不参与任何业务判断 */
function syncSteps() {
    const hasScanned = state.items.some(x => x.status === 'done');
    const done = {
        'sec-owner':  !!(state.profile && state.profile.name),
        'sec-llm':    lastProfiles.some(p => p.active),
        'sec-upload': hasScanned,
        'sec-review': hasScanned && unresolvedCount() === 0,
        'sec-score':  !!sc.report,
        'sec-export': exported
    };
    document.querySelectorAll('.step').forEach(a => {
        a.dataset.done = done[a.dataset.target] ? '1' : '0';
    });
}

// ── 环境状态 ────────────────────────────────────────────────────────
async function refreshStatus() {
    try {
        const resp = await fetch('/api/status');
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const d = await resp.json();

        const rules = d.rules || [];
        const rulesEl = $('rules');
        if (rules.length > 0) {
            rulesEl.textContent = rules.length + ' 份 · ' + rules.map(r => r.college).join('、');
            rulesEl.dataset.state = 'ok';
        } else {
            rulesEl.textContent = '未加载';
            rulesEl.dataset.state = 'warn';
        }

        const llm = d.llm || {};
        const llmEl = $('llm');
        if (llm.configured) {
            llmEl.textContent = '就绪 · ' + (llm.name || llm.masked || '');
            llmEl.dataset.state = 'ok';
        } else if (llm.masked) {
            llmEl.textContent = 'Key 待验证';
            llmEl.dataset.state = 'warn';
        } else {
            llmEl.textContent = '未配置';
            llmEl.dataset.state = 'warn';
        }

        const problems = d.problems || [];
        if (problems.length > 0) {
            $('alert').style.display = 'block';
            $('alert-body').innerHTML = problems.map(p => '<div>· ' + esc(p) + '</div>').join('');
            if (rules.length === 0) rulesEl.dataset.state = 'bad';
        }

        // 侧栏只有 200px 宽，长模型名会被省略号截掉，鼠标悬停要能看到全文
        rulesEl.title = rulesEl.textContent;
        llmEl.title = llmEl.textContent;
    } catch (e) {
        $('rules').textContent = '接口不可用';
        $('rules').dataset.state = 'bad';
    }
}

// ── ① 我的信息 ──────────────────────────────────────────────────────
async function loadProfile() {
    try {
        const r = await fetch('/api/profile');
        const payload = await r.json().catch(() => ({}));
        const d = payload.data || {};
        state.profile = d;
        $('ow-name').value = d.name || '';
        $('ow-sid').value = d.studentId || '';
        $('ow-class').value = d.className || '';
        $('ow-college').value = d.college || '';
        $('ow-grade').value = d.grade || '';
        if (!d.hasName) {
            setMsg($('ow-msg'), '', '还没填姓名。填上以后所有材料统一采用它，模型读错字也不影响结果。');
        } else {
            setMsg($('ow-msg'), 'good', '已保存。材料里的姓名统一用「' + d.name + '」。');
        }
        syncSteps();
    } catch (e) {
        setMsg($('ow-msg'), 'err', '读取本人信息失败：' + e);
    }
}

$('ow-save').addEventListener('click', async () => {
    const nameNew = $('ow-name').value.trim();
    if (!nameNew) {
        setMsg($('ow-msg'), 'err', '姓名必填——它是判断"这份材料是不是你的"的唯一依据。');
        $('ow-name').focus();
        return;
    }
    const body = {
        name: nameNew,
        studentId: $('ow-sid').value.trim(),
        className: $('ow-class').value.trim(),
        college: $('ow-college').value.trim(),
        grade: $('ow-grade').value.trim()
    };
    $('ow-save').disabled = true;
    try {
        const r = await fetch('/api/profile', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('ow-msg'), 'err', payload.message || ('保存失败（HTTP ' + r.status + '）'));
            return;
        }
        state.profile = payload.data || body;

        // 已经扫过的材料：直接把姓名刷新成新值，不用重扫（省时间省钱）
        let patched = 0;
        state.items.forEach(it => {
            const pages = (it.data && it.data.pages) || [];
            pages.forEach((p, i) => {
                if (p.fact && p.fact.person_name !== nameNew) {
                    p.fact.person_name = nameNew;
                    patched++;
                }
                // 姓名相关的歧义项现在都统一了，视为已确认
                (p.issues || []).forEach(iss => {
                    if (iss.field === 'person_name') {
                        state.resolved.add(it.key + ':' + i + ':person_name');
                    }
                });
            });
        });
        renderQueue();
        renderReview();
        setMsg($('ow-msg'), 'good', '已保存。' + (patched > 0
            ? '已同步更新 ' + patched + ' 处已识别材料的姓名，不用重新扫描。'
            : '之后扫的材料都会用这个名字。'));
        syncSteps();
    } catch (e) {
        setMsg($('ow-msg'), 'err', '请求失败：' + e);
    } finally {
        $('ow-save').disabled = false;
    }
});

// ── ② 模型配置：列表 / 新增 / 编辑 / 启用 / 删除（CC-SWITCH 式） ────
let editingId = null;      // null = 新增模式；有值 = 正在编辑该配置
let lastProfiles = [];

async function refreshProfiles() {
    const resp = await fetch('/api/llm');
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    const payload = await resp.json();
    lastProfiles = (payload.data && payload.data.profiles) || [];
    renderProfiles(lastProfiles);
}

function renderProfiles(profiles) {
    const box = $('profiles');
    if (!profiles.length) {
        box.innerHTML = '<div class="empty">还没有配置。点下面「＋ 新增配置」，选厂商、粘贴 Key 就行。</div>';
        $('key-clear-all').style.display = 'none';
        syncSteps();
        return;
    }
    box.innerHTML = profiles.map(p => {
        const on = p.active ? '<span class="badge ok">启用中</span>' : '';
        const prov = p.providerLabel || p.provider || '';
        const title = (p.name && p.name.trim()) || prov || '未命名配置';

        // 默认配置名往往就是「厂商 · 模型」拼出来的，副标题再原样重复一遍纯属噪音。
        // 逐段判断标题里有没有出现过，避免「DeepSeek · deepseek-flash」下面又写一遍。
        const parts = [];
        if (prov && title.indexOf(prov) < 0) parts.push(esc(prov));
        if (p.model && title.indexOf(p.model) < 0) parts.push(esc(p.model));
        if (p.masked) parts.push(esc(p.masked));

        const actions =
            (p.active ? '' : '<button class="mini" data-act="use" data-id="' + esc(p.id) + '">启用</button>')
            + '<button class="mini" data-act="edit" data-id="' + esc(p.id) + '">编辑</button>'
            + '<button class="mini" data-act="del" data-id="' + esc(p.id) + '">删除</button>';
        return '<div class="p-card' + (p.active ? ' on' : '') + '">'
            + '<div class="p-main">'
            + '<div class="p-name">' + esc(title) + on + '</div>'
            + '<div class="p-meta">' + parts.join(' · ') + '<br>' + esc(p.baseUrl) + '</div>'
            + '</div>'
            + '<div class="p-actions">' + actions + '</div>'
            + '</div>';
    }).join('');
    $('key-clear-all').style.display = 'inline-block';
    syncSteps();
}

function openForm(id) {
    editingId = id || null;
    const p = id ? lastProfiles.find(x => x.id === id) : null;
    $('form-title').textContent = p ? '编辑配置' : '新增配置';
    $('key-name').value = p ? p.name : '';
    $('key-provider').value = p ? (p.provider || 'custom') : 'deepseek';
    $('key-input').value = '';
    $('key-input').placeholder = p ? '留空表示不修改已保存的 Key' : '粘贴 API Key';
    $('key-input-hint').style.display = p ? 'inline' : 'none';
    const dft = PROVIDER_DEFAULTS[$('key-provider').value] || { baseUrl: '', model: '' };
    $('key-baseurl').value = p ? p.baseUrl : dft.baseUrl;
    $('key-model').value = p ? p.model : dft.model;
    setMsg($('key-msg'), '', p ? ('当前 Key：' + (p.masked || '未保存') + '（留空则不修改）') : '');
    $('key-form').style.display = 'block';
    $('key-name').focus();
}

function closeForm() {
    editingId = null;
    $('key-form').style.display = 'none';
    $('key-msg').textContent = '';
    ['key-name', 'key-input', 'key-baseurl', 'key-model'].forEach(k => { $(k).value = ''; });
}

async function refreshAll() {
    await refreshStatus();
    await refreshProfiles().catch(() => {});
}

$('key-provider').addEventListener('change', () => {
    const d = PROVIDER_DEFAULTS[$('key-provider').value] || { baseUrl: '', model: '' };
    $('key-baseurl').value = d.baseUrl;
    $('key-model').value = d.model;
});

$('prof-new').addEventListener('click', () => openForm(null));
$('key-cancel').addEventListener('click', closeForm);

$('profiles').addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-act]');
    if (!btn) return;
    const id = btn.getAttribute('data-id');
    const act = btn.getAttribute('data-act');
    const note = $('key-note');

    if (act === 'edit') {
        openForm(id);
        return;
    }
    if (act === 'use') {
        btn.disabled = true;
        try {
            const r = await fetch('/api/llm/active', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            });
            const payload = await r.json().catch(() => ({}));
            if (!r.ok) {
                setMsg(note, 'err', payload.message || ('切换失败（HTTP ' + r.status + '）'));
                return;
            }
            await refreshAll();
            setMsg(note, 'good', '已切换启用配置。');
        } catch (err) {
            setMsg(note, 'err', '请求失败：' + err);
        } finally {
            btn.disabled = false;
        }
        return;
    }
    if (act === 'del') {
        if (!confirm('删除这份配置？Key 会一起从本机删除，不可恢复。')) return;
        try {
            const r = await fetch('/api/llm/key?id=' + encodeURIComponent(id), { method: 'DELETE' });
            const payload = await r.json().catch(() => ({}));
            if (!r.ok) {
                setMsg(note, 'err', payload.message || ('删除失败（HTTP ' + r.status + '）'));
                return;
            }
            if (editingId === id) closeForm();
            await refreshAll();
            setMsg(note, '', '已删除该配置。');
        } catch (err) {
            setMsg(note, 'err', '请求失败：' + err);
        }
    }
});

$('key-save').addEventListener('click', async () => {
    const msgEl = $('key-msg');
    const apiKey = $('key-input').value.trim();
    if (!apiKey && !editingId) {
        setMsg(msgEl, 'err', '先粘贴 Key。');
        return;
    }
    const model = $('key-model').value.trim();
    if (!model) {
        setMsg(msgEl, 'err', '模型名不能为空。');
        return;
    }
    const body = {
        provider: $('key-provider').value,
        name: $('key-name').value.trim(),
        baseUrl: $('key-baseurl').value.trim(),
        model: model
    };
    if (editingId) body.id = editingId;
    if (apiKey) body.apiKey = apiKey;      // 编辑时留空 = 不改原 Key

    $('key-save').disabled = true;
    $('key-cancel').disabled = true;
    setMsg(msgEl, '', '正在测试连通性…通常 1 到 3 秒。');

    try {
        const resp = await fetch('/api/llm/key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const payload = await resp.json().catch(() => ({}));
        if (!resp.ok) {
            setMsg(msgEl, 'err', payload.message || ('保存失败（HTTP ' + resp.status + '）'));
            return;
        }
        closeForm();
        await refreshAll();
        setMsg($('key-note'), 'good', '已保存并验证通过，当前启用这份配置。');
        syncSteps();
    } catch (err) {
        setMsg(msgEl, 'err', '请求失败：' + err);
    } finally {
        $('key-save').disabled = false;
        $('key-cancel').disabled = false;
    }
});

$('key-clear-all').addEventListener('click', async () => {
    if (!confirm('清除本机保存的全部模型配置？所有 Key 都会被删除，不可恢复。')) return;
    try {
        const r = await fetch('/api/llm/key', { method: 'DELETE' });
        if (!r.ok) {
            setMsg($('key-note'), 'err', '清除失败（HTTP ' + r.status + '）');
            return;
        }
        closeForm();
        await refreshAll();
        setMsg($('key-note'), '', '已清除全部配置。');
    } catch (err) {
        setMsg($('key-note'), 'err', '请求失败：' + err);
    }
});

// ── ③ 上传材料：多选 / 拖拽 → 队列 → 逐份识别 ──────────────────────

const ACCEPT_EXT = /\.(pdf|docx?|jpe?g|png|webp)$/i;

// 单份上限。PDF 会被逐页按 150dpi 渲染成图片，所以文件本身大不影响识别耗时；
// 放窄了反而会把「活动合影多、扫描件页数多」这类正常材料挡在外面。
const MAX_FILE_MB = 20;

function addFiles(fileList) {
    const skipped = [];
    Array.from(fileList || []).forEach(f => {
        if (!ACCEPT_EXT.test(f.name)) { skipped.push(f.name + '（格式不支持）'); return; }
        if (f.size > MAX_FILE_MB * 1024 * 1024) {
            skipped.push(f.name + '（超过 ' + MAX_FILE_MB + 'MB）');
            return;
        }
        const dup = state.items.some(x => x.file.name === f.name && x.file.size === f.size);
        if (dup) { skipped.push(f.name + '（重复，已忽略）'); return; }
        state.items.push({
            key: ++state.seq,
            file: f,
            status: 'pending',
            error: null,
            data: null,
            open: false
        });
    });
    if (skipped.length) setMsg($('msg'), 'err', '已跳过：' + skipped.join('、'));
    else setMsg($('msg'), '', '已加入 ' + state.items.length + ' 份材料。点「开始识别」逐个扫描。');
    renderQueue();
}

$('pick').addEventListener('click', e => { e.stopPropagation(); $('file').click(); });
$('zone').addEventListener('click', () => $('file').click());
$('file').addEventListener('change', e => {
    addFiles(e.target.files);
    e.target.value = '';       // 允许重复选同一个文件
});

['dragenter', 'dragover'].forEach(ev =>
    $('zone').addEventListener(ev, e => { e.preventDefault(); $('zone').classList.add('over'); }));
['dragleave', 'drop'].forEach(ev =>
    $('zone').addEventListener(ev, e => { e.preventDefault(); $('zone').classList.remove('over'); }));
$('zone').addEventListener('drop', e => {
    if (e.dataTransfer && e.dataTransfer.files) addFiles(e.dataTransfer.files);
});

$('btn-clear').addEventListener('click', () => {
    if (state.running) return;
    state.items = [];
    state.resolved.clear();
    $('sum').style.display = 'none';
    setMsg($('msg'), '', '队列已清空。');
    renderQueue();
    renderReview();
});

$('btn').addEventListener('click', async () => {
    if (state.running) return;
    const pending = state.items.filter(x => x.status === 'pending');
    if (!pending.length) {
        setMsg($('msg'), 'err', '队列里没有待识别的材料。');
        return;
    }
    state.running = true;
    $('btn').disabled = true;
    $('btn-clear').disabled = true;
    $('btn').textContent = '识别中…';

    let done = 0;
    const startedAll = Date.now();
    for (const it of pending) {
        it.status = 'running';
        renderQueue();
        setMsg($('msg'), '', '正在识别第 ' + (done + 1) + ' / ' + pending.length
            + ' 份：' + it.file.name + '（单份通常 5 到 30 秒，别关页面）');
        try {
            const form = new FormData();
            form.append('file', it.file);
            const resp = await fetch('/api/extract', { method: 'POST', body: form });
            const payload = await resp.json().catch(() => ({}));
            if (!resp.ok) throw new Error((payload && payload.message) || ('HTTP ' + resp.status));
            it.data = payload.data || {};
            it.status = 'done';
            // 有问题就自动展开，让用户一眼看到要改什么
            it.open = countIssuesOf(it) > 0;
        } catch (e) {
            it.status = 'error';
            it.error = (e && e.message) ? e.message : String(e);
            it.open = true;
        }
        done++;
        renderQueue();
        renderReview();
    }

    state.running = false;
    $('btn').textContent = '开始识别';
    updateButtons();
    // 必须在这里再刷一次：最后一轮 renderQueue 时 running 还是 true，
    // 算分按钮被置灰后就再没人把它点亮（实测：批量扫完，「开始算分」一直是灰的）
    updateScoreHint();
    const secs = ((Date.now() - startedAll) / 1000).toFixed(1);
    const failed = state.items.filter(x => x.status === 'error').length;
    const left = unresolvedCount();
    setMsg($('msg'), failed ? 'err' : (left ? '' : 'good'),
        '全部扫完，用时 ' + secs + ' 秒。'
        + (failed ? failed + ' 份失败，展开看原因。' : '')
        + (left ? '有 ' + left + ' 项需要你确认，见下方「待确认」。' : '没有需要确认的字段。'));
    syncSteps();
});

function updateButtons() {
    const hasPending = state.items.some(x => x.status === 'pending');
    $('btn').disabled = state.running || !hasPending;
    $('btn-clear').disabled = state.running || state.items.length === 0;
    const total = state.items.length;
    $('queue-hint').textContent = total
        ? '共 ' + total + ' 份 · ' + fmtSize(state.items.reduce((s, x) => s + x.file.size, 0))
          + (hasPending ? ' · 待识别 ' + state.items.filter(x => x.status === 'pending').length + ' 份' : '')
        : '';
}

function countIssuesOf(it) {
    let n = 0;
    ((it.data && it.data.pages) || []).forEach((p, i) => {
        (p.issues || []).forEach(iss => {
            if (!state.resolved.has(it.key + ':' + i + ':' + iss.field)) n++;
        });
    });
    return n;
}

function valueOf(fact, field) {
    const v = fact[field];
    if (typeof v === 'boolean') return v ? '是' : '否';
    return v == null ? '' : String(v);
}

function renderQueue() {
    const box = $('queue');
    if (!state.items.length) {
        box.innerHTML = '';
        updateButtons();
        updateSum();
        syncSteps();
        return;
    }
    box.innerHTML = state.items.map((it, idx) => {
        const sel = it.status;
        let badge = '<span class="badge mute">待识别</span>';
        if (sel === 'running') badge = '<span class="badge warn">识别中…</span>';
        if (sel === 'error') badge = '<span class="badge bad">失败</span>';
        if (sel === 'done') {
            const n = countIssuesOf(it);
            badge = n
                ? '<span class="badge warn">' + n + ' 项待确认</span>'
                : '<span class="badge ok">无问题</span>';
        }
        const meta = [fmtSize(it.file.size)];
        if (sel === 'done' && it.data) {
            if (it.data.pages) meta.push(it.data.pages.length + ' 页');
            if (it.data.latencyMs) meta.push((it.data.latencyMs / 1000).toFixed(1) + ' 秒');
            if (it.data.totalTokens != null) meta.push(it.data.totalTokens + ' tokens');
            if (it.data.note) meta.push(it.data.note);
        }
        if (sel === 'error') meta.push(it.error || '');

        return '<div class="f-item ' + (sel === 'done' ? (countIssuesOf(it) ? 'run' : 'ok')
                : sel === 'error' ? 'err' : sel === 'running' ? 'run' : '')
            + (it.open ? ' open' : '') + '" data-key="' + it.key + '">'
            + '<div class="f-head" data-role="toggle" data-key="' + it.key + '">'
            + '<span class="f-no">' + String(idx + 1).padStart(2, '0') + '</span>'
            + '<div class="f-main">'
            + '<div class="f-name">' + esc(it.file.name) + '</div>'
            + '<div class="f-meta">' + esc(meta.join(' · ')) + '</div>'
            + '</div>'
            + badge
            + '<span class="chev">▼</span>'
            + '</div>'
            + '<div class="f-body">' + bodyOf(it) + '</div>'
            + '</div>';
    }).join('');
    updateButtons();
    updateSum();
    updateScoreHint();
    syncSteps();
}

function bodyOf(it) {
    if (it.status === 'pending') return '<div class="msg">还没开始识别。</div>';
    if (it.status === 'running') return '<div class="msg">识别中，请稍等…</div>';
    if (it.status === 'error') return '<div class="msg err">识别失败：' + esc(it.error) + '</div>';

    const pages = (it.data && it.data.pages) || [];
    if (!pages.length) return '<div class="msg err">没有识别出任何页面。</div>';

    let html = '';
    pages.forEach((p, pi) => {
        if (pages.length > 1) html += '<div class="page-title">第 ' + (pi + 1) + ' 页</div>';
        html += factTable(it.key, pi, p.fact || {});
    });
    return html;
}

function factTable(key, page, fact) {
    let html = '<table><tbody>';
    for (const [field, label, ro] of FIELDS) {
        const val = valueOf(fact, field);
        if (ro) {
            html += '<tr><th>' + label + '</th><td>'
                + (val === '' ? '<span style="color:#aab3c0">—</span>' : esc(val)) + '</td></tr>';
        } else {
            html += '<tr><th>' + label + '</th><td>'
                + '<input class="fi' + (val === '' ? ' empty' : '') + '" type="text"'
                + ' data-key="' + key + '" data-page="' + page + '" data-field="' + field + '"'
                + ' value="' + esc(val) + '"'
                + (val === '' ? ' placeholder="（未识别到）"' : '') + '>'
                + '</td></tr>';
        }
    }
    return html + '</tbody></table>';
}

// 折叠/展开
$('queue').addEventListener('click', e => {
    const head = e.target.closest('[data-role="toggle"]');
    if (!head) return;
    const key = Number(head.getAttribute('data-key'));
    const it = state.items.find(x => x.key === key);
    if (!it) return;
    it.open = !it.open;
    const card = $('queue').querySelector('.f-item[data-key="' + key + '"]');
    if (card) card.classList.toggle('open', it.open);
});

// 字段编辑：change 而不是 input，避免每敲一个字就回写
$('queue').addEventListener('change', e => {
    const input = e.target.closest('input.fi');
    if (!input) return;
    applyEdit(Number(input.getAttribute('data-key')),
        Number(input.getAttribute('data-page')),
        input.getAttribute('data-field'),
        input.value.trim());
});

$('review').addEventListener('change', e => {
    const input = e.target.closest('input.fi');
    if (!input) return;
    applyEdit(Number(input.getAttribute('data-key')),
        Number(input.getAttribute('data-page')),
        input.getAttribute('data-field'),
        input.value.trim());
});

/**
 * 回写一个字段，并同步所有同坐标的输入框。
 * 注意：这里刻意不做整块重渲染——change 在 blur 时触发，
 * 如果重建 DOM，用户刚点进去的下一个输入框会失去焦点。
 */
function applyEdit(key, page, field, value) {
    const it = state.items.find(x => x.key === key);
    if (!it || !it.data || !it.data.pages || !it.data.pages[page]) return;
    const fact = it.data.pages[page].fact;
    if (!fact) return;
    fact[field] = value === '' ? null : value;

    document.querySelectorAll(
        '[data-key="' + key + '"][data-page="' + page + '"][data-field="' + field + '"]'
    ).forEach(el => {
        if (el.value !== value) el.value = value;
        el.classList.toggle('empty', value === '');
    });

    state.resolved.add(key + ':' + page + ':' + field);
    markIssueDone(key, page, field);
    refreshCounts();
}

function markIssueDone(key, page, field) {
    document.querySelectorAll(
        '.iss[data-key="' + key + '"][data-page="' + page + '"][data-field="' + field + '"]'
    ).forEach(el => el.classList.add('done'));
}

// 只更新计数与徽章，不重建 DOM
function refreshCounts() {
    const left = unresolvedCount();
    $('review-count').textContent = left + ' 项';
    $('review-count').className = 'badge ' + (left ? 'warn' : 'ok');
    state.items.forEach(it => {
        if (it.status !== 'done') return;
        const card = $('queue').querySelector('.f-item[data-key="' + it.key + '"]');
        if (!card) return;
        const n = countIssuesOf(it);
        const badge = card.querySelector('.f-head .badge');
        if (badge) {
            badge.textContent = n ? n + ' 项待确认' : '无问题';
            badge.className = 'badge ' + (n ? 'warn' : 'ok');
        }
        card.classList.toggle('run', n > 0);
        card.classList.toggle('ok', n === 0);
    });
    updateSum();
    syncSteps();
}

function unresolvedCount() {
    let n = 0;
    state.items.forEach(it => {
        ((it.data && it.data.pages) || []).forEach((p, i) => {
            (p.issues || []).forEach(iss => {
                if (!state.resolved.has(it.key + ':' + i + ':' + iss.field)) n++;
            });
        });
    });
    return n;
}

// ── ④ 待确认清单 ────────────────────────────────────────────────────

const REASON_LABEL = {
    auto_filled: '已自动补全',
    name_mismatch: '姓名不一致',
    missing: '没读到',
    low_confidence: '可信度低'
};

function renderReview() {
    const rows = [];
    state.items.forEach((it, idx) => {
        ((it.data && it.data.pages) || []).forEach((p, pi) => {
            (p.issues || []).forEach(iss => {
                rows.push({ it, idx, pi, iss, done: state.resolved.has(it.key + ':' + pi + ':' + iss.field) });
            });
        });
    });

    const sec = $('sec-review');
    if (!rows.length) {
        sec.style.display = 'none';
        $('review').innerHTML = '';
        syncSteps();
        return;
    }
    sec.style.display = '';

    const left = rows.filter(r => !r.done).length;
    $('review-count').textContent = left + ' 项';
    $('review-count').className = 'badge ' + (left ? 'warn' : 'ok');

    $('review').innerHTML =
        '<div class="hint" style="margin-bottom:4px">共 ' + rows.length + ' 项提示，其中 '
        + left + ' 项还没确认。左边是材料·字段，右边直接改，改完自动标记为已确认。</div>'
        + rows.map(r => {
            const fact = (r.it.data.pages[r.pi] || {}).fact || {};
            const cur = valueOf(fact, r.iss.field);
            const pageTag = ((r.it.data.pages || []).length > 1) ? (' · 第 ' + (r.pi + 1) + ' 页') : '';
            return '<div class="iss' + (r.done ? ' done' : '') + '"'
                + ' data-key="' + r.it.key + '" data-page="' + r.pi
                + '" data-field="' + esc(r.iss.field) + '">'
                + '<div class="iss-main">'
                + '<div class="iss-t">材料 ' + (r.idx + 1) + pageTag + ' · '
                + esc(r.iss.label || r.iss.field)
                + ' <span class="badge mute">' + esc(REASON_LABEL[r.iss.reason] || r.iss.reason) + '</span></div>'
                + '<div class="iss-h">' + esc(r.iss.hint || '') + '</div>'
                + '</div>'
                + '<input class="fi iss-in' + (cur === '' ? ' empty' : '') + '" type="text"'
                + ' data-key="' + r.it.key + '" data-page="' + r.pi
                + '" data-field="' + esc(r.iss.field) + '"'
                + ' value="' + esc(cur) + '"'
                + (cur === '' ? ' placeholder="填上正确值"' : '') + '>'
                + '</div>';
        }).join('');
    syncSteps();
}

function updateSum() {
    const doneItems = state.items.filter(x => x.status === 'done');
    if (!doneItems.length) { $('sum').style.display = 'none'; return; }
    let pages = 0, ms = 0, tokens = 0, tokenKnown = false;
    doneItems.forEach(it => {
        pages += (it.data.pages || []).length;
        ms += it.data.latencyMs || 0;
        if (it.data.totalTokens != null) { tokens += it.data.totalTokens; tokenKnown = true; }
    });
    $('sum').style.display = 'flex';
    $('sum').innerHTML =
        '<span>已完成 <b>' + doneItems.length + '</b> 份 / <b>' + pages + '</b> 页</span>'
        + '<span>耗时 <b>' + (ms / 1000).toFixed(1) + '</b> 秒</span>'
        + '<span>用量 <b>' + (tokenKnown ? tokens.toLocaleString() : '未知') + '</b> tokens</span>'
        + '<span>待确认 <b>' + unresolvedCount() + '</b> 项</span>';
}

// ── ⑤ 算分 ──────────────────────────────────────────────────────────
// 后端是纯函数：每次都把「材料 + 输入 + 候选选择 + 排除/强制」整包发过去重算，
// 前端不自己算分（否则两套逻辑迟早对不上）。
const sc = {
    meta: null,
    inputs: {},
    choices: {},
    excluded: new Set(),
    forced: new Set(),
    report: null,
    matCount: 0
};

async function loadScoreMeta() {
    try {
        const r = await fetch('/api/score/meta');
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('sc-msg'), 'err', (payload && payload.message) || ('读取规则失败：HTTP ' + r.status));
            $('sc-btn').disabled = true;
            return;
        }
        const d = sc.meta = payload.data || {};
        $('sc-rule').value = [d.school, d.college, d.ruleVersion].filter(Boolean).join(' / ');

        const years = d.academicYears || [];
        $('sc-year').innerHTML = years.map(y =>
            '<option value="' + esc(y.label) + '">' + esc(y.label)
            + '（' + esc(y.start) + ' ~ ' + esc(y.end) + '）</option>').join('');
        $('sc-year').value = d.defaultYear || (years[0] && years[0].label) || '';

        let html = '';
        (d.dimensions || []).forEach(dim => {
            (dim.inputs || []).forEach(inp => {
                // 「德育基础分」已经带了维度名，别再拼成「德育 · 德育基础分」
                const name = inp.name || inp.key;
                const label = name.indexOf(dim.name) === 0 ? name : dim.name + ' · ' + name;
                html += '<div class="field"><label>' + esc(label) + '</label>'
                    + '<input type="number" step="0.01" min="0" data-sc-key="' + esc(inp.key) + '"'
                    + ' placeholder="' + esc(inp.hint || '填数字') + '"></div>';
            });
        });
        $('sc-inputs').innerHTML = html
            ? '<hr class="sep"><div class="form-title">基础分 · 证书里读不到，必须手填</div>'
              + '<div class="grid2">' + html + '</div>'
            : '';

        if (!d.hasStructuredRules) {
            setMsg($('sc-msg'), 'err', '当前细则还没有任何加分明细，算分只能算基础分部分。');
        } else if ((d.configProblems || []).length) {
            setMsg($('sc-msg'), 'err', '规则配置有问题：' + d.configProblems.join('；'));
        }
        updateScoreHint();
    } catch (e) {
        setMsg($('sc-msg'), 'err', '读取算分信息失败：' + e);
    }
}

/**
 * 收集参与算分的材料，同时记下「每份材料来自哪个原件」。
 *
 * 一份 3 页的 PDF 会变成 3 份材料，但打包时只有 1 个原件，
 * 所以 sources 这个映射必须在同一处产生——两处各写一遍迟早错位。
 */
function collectMaterials() {
    const materials = [];
    const sources = [];
    const files = [];
    state.items.forEach(it => {
        if (it.status !== 'done' || !it.data) return;
        const pages = it.data.pages || [];
        if (!pages.some(p => p.fact)) return;
        const src = files.length;
        files.push(it.file);
        pages.forEach((p, pi) => {
            if (!p.fact) return;
            const suffix = pages.length > 1 ? (' · 第' + (pi + 1) + '页') : '';
            materials.push({ fileName: it.file.name + suffix, fact: p.fact });
            sources.push(src);
        });
    });
    return { materials, sources, files };
}

/** 已识别的每「页」都当成一份材料参与算分（一份 PDF 里可能有多张证书） */
function scoreMaterials() {
    return collectMaterials().materials;
}

/** 算分 / 预览 / 导出共用同一份入参，保证三个接口看到的完全是同一件事 */
function scoreRequest(materials) {
    return {
        materials: materials || scoreMaterials(),
        academicYear: $('sc-year').value,
        inputs: sc.inputs,
        choices: sc.choices,
        excluded: Array.from(sc.excluded),
        forced: Array.from(sc.forced)
    };
}

function updateScoreHint() {
    const n = scoreMaterials().length;
    $('sc-hint').textContent = n ? ('当前可算分材料：' + n + ' 份') : '还没有已识别的材料，先在上面扫描';
    $('sc-btn').disabled = n === 0 || state.running;
}

async function runScore() {
    const materials = scoreMaterials();
    if (!materials.length) {
        setMsg($('sc-msg'), 'err', '没有可算分的材料。');
        return;
    }
    // 材料份数变了说明重扫过，旧的候选/排除记录会错位，清掉
    if (materials.length !== sc.matCount) {
        sc.choices = {};
        sc.excluded = new Set();
        sc.forced = new Set();
        sc.matCount = materials.length;
    }
    $('sc-btn').disabled = true;
    setMsg($('sc-msg'), '', '正在按细则算分…');
    try {
        const r = await fetch('/api/score', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                materials,
                academicYear: $('sc-year').value,
                inputs: sc.inputs,
                choices: sc.choices,
                excluded: Array.from(sc.excluded),
                forced: Array.from(sc.forced)
            })
        });
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('sc-msg'), 'err', payload.message || ('算分失败（HTTP ' + r.status + '）'));
            return;
        }
        sc.report = payload.data;
        renderScore(sc.report);
        // 分数一变，之前那份打包预览就过期了，直接清掉比留着误导强
        if ($('ex-result')) $('ex-result').innerHTML = '';
        // 按「材料 × 规则组」数待确认，而不是按候选行数——同一项有 3 个候选不该算 3 条
        const left = new Set((sc.report.lines || [])
            .filter(l => l.needsConfirm)
            .map(l => l.materialIndex + ':' + l.groupId)).size;
        setMsg($('sc-msg'), left ? '' : 'good',
            '算分完成。' + (left ? '有 ' + left + ' 项待确认，可以直接改候选或勾掉，结果会实时重算。'
                                 : '没有需要确认的条目。'));
    } catch (e) {
        setMsg($('sc-msg'), 'err', '请求失败：' + e);
    } finally {
        $('sc-btn').disabled = false;
    }
}

function renderScore(rep) {
    let html = '';

    // 总分
    if (rep.overall != null) {
        html += '<div class="total"><span class="num">' + rep.overall.toFixed(2) + '</span>'
            + '<span class="lbl">总分<br>' + esc(rep.academicYear) + ' 学年 · '
            + esc(rep.school + ' ' + rep.college) + '</span></div>';
    } else {
        html += '<div class="total"><span class="num" style="color:#aab3c0">—</span>'
            + '<span class="warn">总分还算不出来：有维度缺基础分，上面填上就出结果</span></div>';
    }

    // 维度
    html += '<div class="dims">';
    (rep.dimensions || []).forEach(d => {
        const na = d.score == null;
        const b = d.bonus || 0;
        const m = d.deduction || 0;
        html += '<div class="dim' + (na ? ' need' : '') + '">'
            + '<div class="dim-top"><span class="dn">' + esc(d.name) + '</span>'
            + '<span class="dw">' + (d.weight * 100).toFixed(0) + '%</span></div>'
            + '<div class="ds' + (na ? ' na' : '') + '">' + (na ? '待填' : d.score.toFixed(2)) + '</div>'
            + '<div class="df" title="占 ' + (d.weight * 100).toFixed(0) + '%，加分 ' + fmtNum(b)
            + '，扣分 ' + fmtNum(m) + '">'
            + '<span class="' + (b > 0 ? 'up' : 'zero') + '">+' + fmtNum(b) + '</span>'
            + '<span class="sl">/</span>'
            + '<span class="' + (m > 0 ? 'down' : 'zero') + '">-' + fmtNum(m) + '</span>'
            + '</div>'
            + (d.warnings || []).map(w => '<div class="dim-warn">· ' + esc(w) + '</div>').join('')
            + '</div>';
    });
    html += '</div>';

    // 明细：按材料分组
    const lines = rep.lines || [];
    const byMat = new Map();
    lines.forEach(l => {
        if (!byMat.has(l.materialIndex)) byMat.set(l.materialIndex, []);
        byMat.get(l.materialIndex).push(l);
    });
    Array.from(byMat.keys()).sort((a, b) => a - b).forEach(mi => {
        const rows = byMat.get(mi);
        html += '<div class="mat-block"><div class="mat-name">' + (mi + 1) + '. '
            + esc(rows[0].fileName) + '</div>';
        const byGroup = new Map();
        rows.forEach(l => {
            if (!byGroup.has(l.groupId)) byGroup.set(l.groupId, []);
            byGroup.get(l.groupId).push(l);
        });
        byGroup.forEach(cands => {
            const main = cands.find(c => c.selected) || cands[0];
            html += lineHtml(main, cands);
        });
        html += '</div>';
    });

    // 未匹配
    const un = rep.unmatched || [];
    if (un.length) {
        html += '<div class="unmatched"><b>' + un.length + ' 份材料没能自动算分</b><br>'
            + un.map(u => '· ' + esc(u.fileName) + '：' + esc(u.reason)).join('<br>')
            + '<br><span style="font-size:11px">这几份要么不属于加分项，要么细则里没有对应条目——'
            + '需要你自己对照细则判断，工具不替你猜。</span></div>';
    }

    // 配置/引擎层面的问题
    const issues = rep.issues || [];
    if (issues.length) {
        html += '<div class="unmatched">' + issues.map(i => '· ' + esc(i)).join('<br>') + '</div>';
    }

    $('sc-result').innerHTML = html;
    syncSteps();
}

function lineHtml(l, cands) {
    let ctl = '';
    if (cands.length > 1) {
        ctl += '<select data-act="choice" data-mat="' + l.materialIndex
            + '" data-group="' + esc(l.groupId) + '">'
            + cands.map(c => '<option value="' + esc(c.id) + '"' + (c.id === l.id ? ' selected' : '') + '>'
                + esc(shortBasis(c.basis)) + ' · ' + (c.score == null ? '待定' : c.score + ' 分')
                + '</option>').join('')
            + '</select>';
    }
    ctl += '<label class="chk"><input type="checkbox" data-act="toggle" data-id="' + esc(l.id) + '"'
        + (l.included ? ' checked' : '') + '> 计入合计</label>';

    return '<div class="ln' + (l.included ? '' : ' off') + '">'
        + '<div class="ln-main">'
        + '<div class="ln-t">' + esc(l.groupName)
        + (l.needsConfirm ? ' <span class="badge warn">待确认</span>' : '')
        + (l.inWindow === false ? ' <span class="badge bad">超学年</span>' : '')
        + (l.manual ? ' <span class="badge mute">需人工</span>' : '')
        + '</div>'
        + '<div class="ln-b">依据：' + esc(l.basis || '（细则未写说明）') + '</div>'
        + (l.reason ? '<div class="ln-r">' + esc(l.reason) + '</div>' : '')
        + (l.evidence ? '<div class="ln-b">原文：' + esc(String(l.evidence).slice(0, 70)) + '</div>' : '')
        + '</div>'
        + '<div class="ln-score">' + (l.score == null ? '待定'
            : ((l.score > 0 ? '+' : '') + fmtNum(l.score))) + '</div>'
        + '<div class="ln-ctl">' + ctl + '</div>'
        + '</div>';
}

function shortBasis(s) {
    if (!s) return '未写说明';
    const i = s.indexOf(' · ');
    const head = i > 0 ? s.slice(0, i) : s;
    return head.length > 18 ? head.slice(0, 18) + '…' : head;
}

function fmtNum(v) {
    if (v == null) return '0';
    return Number.isInteger(v) ? String(v) : String(Math.round(v * 100) / 100);
}

$('sc-btn').addEventListener('click', runScore);

$('sc-year').addEventListener('change', () => {
    if (sc.report) runScore();          // 换学年直接重算
});

$('sc-inputs').addEventListener('input', e => {
    const el = e.target.closest('input[data-sc-key]');
    if (!el) return;
    const k = el.getAttribute('data-sc-key');
    const v = el.value.trim();
    if (v === '') delete sc.inputs[k];
    else sc.inputs[k] = Number(v);
});

$('sc-result').addEventListener('change', e => {
    const el = e.target;
    if (el.dataset.act === 'choice') {
        const mat = el.dataset.mat;
        const group = el.dataset.group;
        // 改选候选时，把原候选身上的「强制计入 / 手动排除」标记跟着搬到新候选上，
        // 否则用户刚勾好"计入"，一换候选就又被学年窗口踢出去，看起来很莫名其妙
        const prev = ((sc.report && sc.report.lines) || [])
            .find(l => String(l.materialIndex) === mat && l.groupId === group && l.selected);
        if (prev && prev.id !== el.value) {
            if (sc.forced.has(prev.id)) {
                sc.forced.delete(prev.id);
                sc.forced.add(el.value);
            }
            if (sc.excluded.has(prev.id)) {
                sc.excluded.delete(prev.id);
                sc.excluded.add(el.value);
            }
        }
        sc.choices[mat + ':' + group] = el.value;
        runScore();
    } else if (el.dataset.act === 'toggle') {
        const id = el.dataset.id;
        if (el.checked) {
            sc.excluded.delete(id);
            sc.forced.add(id);
        } else {
            sc.forced.delete(id);
            sc.excluded.add(id);
        }
        runScore();
    }
});

// ── ⑥ 导出 ──────────────────────────────────────────────────────────
// 后端拿同一份入参重算一遍再打包，所以这里不把页面上的分数塞进 zip。
// 理由：前端只要有一次状态不同步，包里的数字就和页面不一致，而这种错用户自己看不出来。

function exportReady() {
    if (!sc.report) {
        setMsg($('ex-msg'), 'err', '先点上面的「开始算分」——导出的是算分结果，不是原始识别结果。');
        return false;
    }
    const p = state.profile || {};
    if (!p.name || !p.studentId) {
        setMsg($('ex-msg'), 'err', '压缩包按「学号_姓名」命名，请先在 ① 里把姓名和学号填好。');
        return false;
    }
    return true;
}

async function previewExport() {
    if (!exportReady()) return;
    $('ex-preview').disabled = true;
    setMsg($('ex-msg'), '', '正在生成打包结构…');
    try {
        const r = await fetch('/api/export/preview', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(scoreRequest())
        });
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('ex-msg'), 'err', payload.message || ('预览失败（HTTP ' + r.status + '）'));
            return;
        }
        renderExport(payload.data);
        setMsg($('ex-msg'), 'good', '打包结构如上，确认无误后点「导出 zip」。');
    } catch (e) {
        setMsg($('ex-msg'), 'err', '请求失败：' + e);
    } finally {
        $('ex-preview').disabled = false;
    }
}

function renderExport(d) {
    let html = '<div class="tree"><div class="t-file"><span class="k">压缩包</span><b>' + esc(d.zipName) + '</b>'
        + '<span class="k">·</span>' + (d.packedCount || 0) + ' 份材料</div>';

    (d.folders || []).forEach(f => {
        const items = f.items || [];
        html += '<div class="t-folder' + (items.length ? '' : ' empty') + '">' + esc(f.name)
            + '<span class="n">' + (items.length ? items.length + ' 份' : '空文件夹') + '</span></div>';
        items.forEach(it => {
            html += '<div class="t-item">'
                + '<span class="sc">' + (it.score == null ? '—' : '+' + it.score) + '</span>'
                + '<span class="nm">' + esc(it.newName) + '</span>'
                + '<span class="from">← ' + esc(it.originalName) + '</span></div>';
        });
    });

    html += '<div class="t-folder" style="margin-top:14px">评定表'
        + '<span class="n">' + esc(d.formFileName || '综测评定表.xlsx') + '</span></div>'
        + '<div class="t-item"><span class="from">总表 + 加分明细 两个 sheet</span></div>';

    const skipped = d.skipped || [];
    if (skipped.length) {
        html += '<div class="unmatched"><b>' + skipped.length + ' 份材料没进包</b>'
            + skipped.map(s => '<div>· ' + esc(s.fileName) + '：' + esc(s.reason) + '</div>').join('')
            + '</div>';
    }

    // 后端还会附一条「有 N 份材料没进包」的总计提示，和上面逐条列出的是同一件事，
    // 已经列过就不再重复一遍
    const warns = (d.warnings || []).filter(w => !(skipped.length && /没?进包/.test(w)));
    if (warns.length) {
        html += '<div class="unmatched">' + warns.map(w => '<div>· ' + esc(w) + '</div>').join('') + '</div>';
    }

    $('ex-result').innerHTML = html + '</div>';
}

async function doExport() {
    if (!exportReady()) return;
    const bundle = collectMaterials();
    if (!bundle.files.length) {
        setMsg($('ex-msg'), 'err', '没有可打包的原件。');
        return;
    }

    const fd = new FormData();
    fd.append('request', JSON.stringify(scoreRequest(bundle.materials)));
    fd.append('sources', JSON.stringify(bundle.sources));
    bundle.files.forEach(f => fd.append('files', f, f.name));

    $('ex-btn').disabled = true;
    setMsg($('ex-msg'), '', '正在打包…');
    try {
        const r = await fetch('/api/export', { method: 'POST', body: fd });
        if (!r.ok) {
            const payload = await r.json().catch(() => ({}));
            setMsg($('ex-msg'), 'err', payload.message || ('导出失败（HTTP ' + r.status + '）'));
            return;
        }
        const blob = await r.blob();
        const name = decodeURIComponent(r.headers.get('X-Export-Name') || '') || '综测材料.zip';
        const saved = decodeURIComponent(r.headers.get('X-Export-Path') || '');

        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000);

        exported = true;
        syncSteps();
        setMsg($('ex-msg'), 'good', '已导出 ' + name
            + (saved ? '，本机另存一份：' + saved : '')
            + '。解压后请核对文件树和评定表，再交给班委。');
    } catch (e) {
        setMsg($('ex-msg'), 'err', '请求失败：' + e);
    } finally {
        $('ex-btn').disabled = false;
    }
}

$('ex-preview').addEventListener('click', previewExport);
$('ex-btn').addEventListener('click', doExport);

// ── 页面生命周期：开着报心跳，关了通知后端退出 ──────────────────
// 正常关页 → 后端宽限几秒后退出进程；刷新会立刻恢复心跳所以不会误杀；
// 切到后台只上报 visible=false，不靠心跳超时（浏览器会限流后台标签的定时器）
(function pageLifecycle() {
    const beat = () => {
        const visible = document.visibilityState === 'visible';
        fetch('/api/beat?visible=' + visible, { method: 'POST', keepalive: true }).catch(() => {});
    };
    beat();                                                    // 打开即报一次
    setInterval(() => {
        if (document.visibilityState === 'visible') beat();     // 后台标签不打扰
    }, 5000);
    document.addEventListener('visibilitychange', beat);         // 切走/切回立刻上报
    // 关闭或刷新前通知后端；sendBeacon 在页面卸载时也能发出（fetch 会被取消）
    window.addEventListener('pagehide', () => navigator.sendBeacon('/api/bye'));
})();

// ── 启动 ────────────────────────────────────────────────────
initNav();
window.addEventListener('scroll', spyScroll, { passive: true });
window.addEventListener('resize', spyScroll);
setActiveSec('sec-owner');
syncSteps();

refreshAll();
loadProfile();
loadScoreMeta();
