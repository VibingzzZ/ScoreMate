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

/**
 * 评分规则（③）的状态。
 *
 * <p>draft 是「从细则里读出来、但还没生效」的规则——只有用户点「保存并生效」
 * 才会写进 YAML。刻意不放 in-memory 之外的地方：关掉页面就是放弃这次转换，
 * 当前配置一点没动。
 */
const ruleState = {
    current: null,   // /api/rules/current 返回的概况
    file: null,      // 已选待转换的细则文件
    draft: null,     // 识别出的草稿（结构对应后端 RuleDraft）
    open: new Set(), // 展开的组，key = 维度|组名
    allOpen: false,
    busy: false
};

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
        'sec-rules':  !!(ruleState.current && ruleState.current.groupCount > 0),
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

// ══════════════════════════════════════════════════════════════════
//  ③ 评分规则：上传细则 → 转成草稿 → 人工核对 → 写 YAML
// ══════════════════════════════════════════════════════════════════
// 规则不进代码，进 YAML：换学院、换年份只要重传一份细则。
// 但「上传即生效」不行——加分表是「级别 × 等次」的二维表，模型读错一格
// 就会给整班算错分，而这个错当场看不出来。所以两步：先出草稿让人核对，
// 确认后才覆盖配置（旧文件自动备份）。

const DIM_LABEL = {
    moral: '德育', intellect: '智育', physique: '体育',
    aesthetic: '美育', labour: '劳育'
};

// 与导出文件夹顺序一致（德 / 智 / 美 / 体 / 劳）。页面按同样顺序排，
// 换个年份对照细则时不会因为组序不同而看岔。
const DIM_ORDER = ['moral', 'intellect', 'aesthetic', 'physique', 'labour'];

const MAX_RULE_MB = 30;

async function loadRulesCurrent() {
    try {
        const r = await fetch('/api/rules/current');
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error((payload && payload.message) || ('HTTP ' + r.status));
        ruleState.current = payload.data || {};
        renderRulesCurrent();
        updateRulesButtons();
        syncSteps();
    } catch (e) {
        $('rules-cur').innerHTML = '<span class="hint">读取当前规则失败：' + esc(e.message || e) + '</span>';
    }
}

function renderRulesCurrent() {
    const c = ruleState.current || {};
    const box = $('rules-cur');
    const bits = [];

    if (c.groupCount) {
        bits.push('<span><span class="k">当前</span><b>'
            + esc([c.school, c.college].filter(Boolean).join(' · ') || '未标注学院')
            + '</b></span>');
        if (c.ruleVersion) bits.push('<span><span class="k">版本</span>' + esc(c.ruleVersion) + '</span>');
        bits.push('<span><span class="k">规模</span><b>' + c.groupCount + '</b> 组 · <b>'
            + c.itemCount + '</b> 条</span>');
        if (c.sourceFile) bits.push('<span><span class="k">来源</span>' + esc(c.sourceFile) + '</span>');
        if (c.file) bits.push('<span><span class="k">文件</span>' + esc(c.file) + '</span>');
    } else {
        bits.push('<span class="hint">当前还没有任何加分明细，算分只能算基础分。'
            + '下面传一份学院细则就能补齐。</span>');
    }

    if (!c.ready) {
        bits.push('<span style="color:var(--bad)">模型没配好，暂时不能重新转换（先完成 ②）</span>');
    }
    box.innerHTML = bits.join('');
}

function updateRulesButtons() {
    const ready = !!(ruleState.current && ruleState.current.ready);
    $('rules-import').disabled = !ready || !ruleState.file || ruleState.busy;
    $('rules-hint').textContent = ruleState.file
        ? ruleState.file.name + ' · ' + fmtSize(ruleState.file.size) : '';
}

function setRuleFile(f) {
    if (!f) return;
    if (!/\.(pdf|docx?|jpe?g|png|webp)$/i.test(f.name)) {
        setMsg($('rules-msg'), 'err', '不支持的格式：' + f.name + '。细则一般是 PDF。');
        return;
    }
    if (f.size > MAX_RULE_MB * 1024 * 1024) {
        setMsg($('rules-msg'), 'err', f.name + ' 有 ' + fmtSize(f.size)
            + '，超过 ' + MAX_RULE_MB + 'MB。先用 PDF 压缩工具压一下再传（细则要读每一页，扫描件太大读得也慢）。');
        return;
    }
    ruleState.file = f;
    updateRulesButtons();
    setMsg($('rules-msg'), '', '已选择 ' + f.name + '。点「开始转换」——细则通常 20 到 40 页，'
        + '要 1 分钟出头。转换只生成草稿，不会改动当前规则。');
}

$('rules-pick').addEventListener('click', e => { e.stopPropagation(); $('rules-file').click(); });
$('rules-zone').addEventListener('click', () => $('rules-file').click());
$('rules-file').addEventListener('change', e => {
    setRuleFile(e.target.files && e.target.files[0]);
    e.target.value = '';        // 允许重新选同一个文件
});

['dragenter', 'dragover'].forEach(ev =>
    $('rules-zone').addEventListener(ev, e => { e.preventDefault(); $('rules-zone').classList.add('over'); }));
['dragleave', 'drop'].forEach(ev =>
    $('rules-zone').addEventListener(ev, e => { e.preventDefault(); $('rules-zone').classList.remove('over'); }));
$('rules-zone').addEventListener('drop', e => {
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
        setRuleFile(e.dataTransfer.files[0]);
    }
});

$('rules-import').addEventListener('click', async () => {
    if (ruleState.busy || !ruleState.file) return;
    ruleState.busy = true;
    $('rules-import').disabled = true;
    $('rules-import').textContent = '转换中…';

    // 一个大请求，拿不到真实进度；只有「已经等了多少秒」是诚实的。
    // 30 页细则大约 50–70 秒，没有计时器用户会以为页面卡死。
    const t0 = Date.now();
    const tick = () => setMsg($('rules-msg'), '',
        '正在逐页读取细则…已等待 ' + Math.round((Date.now() - t0) / 1000)
        + ' 秒（' + ruleState.file.name + '）。期间别关页面，读完会把全部条目列出来供你核对。');
    tick();
    const timer = setInterval(tick, 1000);

    try {
        const fd = new FormData();
        fd.append('file', ruleState.file);
        const r = await fetch('/api/rules/import', { method: 'POST', body: fd });
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('rules-msg'), 'err', payload.message || ('转换失败（HTTP ' + r.status + '）'));
            return;
        }
        ruleState.draft = payload.data || {};
        ruleState.allOpen = false;
        $('rules-toggle').textContent = '全部展开';
        // 默认只展开「有未定分条目」的组：200 多条全铺开，用户反而找不到该看哪里
        ruleState.open = new Set((ruleState.draft.groups || [])
            .filter(g => (g.items || []).some(i => i.score == null))
            .map(g => g.dimension + '|' + g.name));

        renderDraft();
        const secs = ((Date.now() - t0) / 1000).toFixed(0);
        setMsg($('rules-msg'), 'good', '读完了，用时 ' + secs + ' 秒。下面逐组核对——'
            + '现在还没改动任何配置，看到不对的分值直接改，多余的点 × 删掉。');
        $('rules-draft-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) {
        setMsg($('rules-msg'), 'err', '请求失败：' + e);
    } finally {
        clearInterval(timer);
        ruleState.busy = false;
        $('rules-import').textContent = '开始转换';
        updateRulesButtons();
    }
});

/** 按维度分组排序，组内顺序保持细则原样（明细行序就是打包顺序） */
function sortedGroups(groups) {
    const rank = g => {
        const i = DIM_ORDER.indexOf(g.dimension);
        return i < 0 ? DIM_ORDER.length : i;
    };
    return (groups || []).slice().sort((a, b) => rank(a) - rank(b));
}

function draftCounts(d) {
    const groups = (d && d.groups) || [];
    const items = groups.reduce((s, g) => s + (g.items || []).length, 0);
    const unscored = groups.reduce(
        (s, g) => s + (g.items || []).filter(i => i.score == null).length, 0);
    return { groups: groups.length, items, unscored };
}

function renderDraft() {
    const d = ruleState.draft;
    const card = $('rules-draft-card');
    if (!d || !(d.groups || []).length) {
        card.style.display = 'none';
        return;
    }
    card.style.display = '';

    // 排好序后写回草稿：保存时 YAML 里的顺序就是这个顺序
    d.groups = sortedGroups(d.groups);

    $('rules-groups').innerHTML = d.groups.map((g, gi) => {
        const key = g.dimension + '|' + g.name;
        const open = ruleState.allOpen || ruleState.open.has(key);
        const items = g.items || [];
        const need = items.filter(i => i.score == null).length;
        return '<div class="rg' + (open ? ' open' : '') + '" data-g="' + gi + '">'
            + '<div class="rg-head" data-role="rg-toggle">'
            + '<span class="rg-dim' + (g.kind === 'penalty' ? ' penalty' : '') + '">'
            + esc(DIM_LABEL[g.dimension] || g.dimension || '?') + '</span>'
            + '<span class="rg-name" title="' + esc(g.name) + '">' + esc(g.name) + '</span>'
            + '<span class="rg-n" data-role="need" style="color:var(--warn)'
            + (need ? '' : ';display:none') + '">' + (need ? need + ' 条待填' : '') + '</span>'
            + '<span class="rg-n">' + items.length + ' 条</span>'
            + '<span class="rg-caret">▶</span>'
            + '</div>'
            + '<div class="rg-items">'
            + items.map((it, ii) => itemHtml(gi, ii, it)).join('')
            + (g.note ? '<div class="rg-note">表格备注：' + esc(g.note) + '</div>' : '')
            + (g.evidence ? '<div class="rg-note">细则原文：' + esc(g.evidence) + '</div>' : '')
            + '</div></div>';
    }).join('');

    if (!$('rules-college').value) {
        $('rules-college').value = (ruleState.current && ruleState.current.college) || '';
    }
    renderDraftStat();
    syncSteps();
}

function itemHtml(gi, ii, it) {
    // 等次常常已经写进标题了（「五星宿舍（寝室长）」+ 等次「五星」），
    // 再补一遍纯属噪音；只有标题里没出现时才单独显示
    const showRank = it.rank && (!it.title || it.title.indexOf(it.rank) < 0);
    const rank = showRank ? ' <span class="rl">' + esc(it.rank) + '</span>' : '';
    const tip = it.title + (it.note ? '｜' + it.note : '');
    return '<div class="ri' + (it.score == null ? ' need' : '') + '">'
        + '<div class="rt" title="' + esc(tip) + '">' + esc(it.title || '（未命名条目）') + rank + '</div>'
        + '<div class="rl">' + esc(it.level || '—') + '</div>'
        + '<input class="rs-input" type="number" step="0.01" min="0"'
        + ' data-g="' + gi + '" data-i="' + ii + '"'
        + ' value="' + (it.score == null ? '' : it.score) + '"'
        + (it.score == null ? ' placeholder="?"' : '') + ' title="分值（可改）">'
        + '<button class="rx" data-act="del-item" data-g="' + gi + '" data-i="' + ii
        + '" title="删掉这条">×</button>'
        + '</div>';
}

/** 只刷统计与提醒，不重建 DOM —— 重建会让正在录入的输入框丢焦点 */
function renderDraftStat() {
    const d = ruleState.draft;
    if (!d) return;
    const c = draftCounts(d);

    const bits = ['共 <b>' + c.groups + '</b> 组 / <b>' + c.items + '</b> 条'];
    if (d.pagesRead) {
        bits.push('读了 ' + d.pagesRead + (d.totalPages ? ' / ' + d.totalPages : '') + ' 页');
    }
    if (d.totalTokens) bits.push('约 ' + d.totalTokens.toLocaleString() + ' tokens');
    if (c.unscored) bits.push('<span style="color:var(--warn)">' + c.unscored + ' 条缺分值</span>');
    $('rules-stat').innerHTML = bits.join(' · ');

    document.querySelectorAll('#rules-groups .rg').forEach(box => {
        const g = d.groups[Number(box.getAttribute('data-g'))];
        if (!g) return;
        const need = (g.items || []).filter(i => i.score == null).length;
        const span = box.querySelector('[data-role="need"]');
        if (!span) return;
        span.style.display = need ? '' : 'none';
        span.textContent = need ? need + ' 条待填' : '';
    });

    const warn = [];
    if (c.unscored) {
        warn.push('有 ' + c.unscored + ' 条没有分值（黄色行）：点开所在的组补上数字，或点 × 删掉，否则不能保存。');
    }
    (d.conflicts || []).forEach(x => warn.push(x));
    (d.problems || []).forEach(x => warn.push(x));

    if (warn.length) {
        $('rules-warn').style.display = 'block';
        $('rules-warn-body').innerHTML = warn.map(w => '<div>· ' + esc(w) + '</div>').join('');
    } else {
        $('rules-warn').style.display = 'none';
    }
}

$('rules-groups').addEventListener('click', e => {
    const del = e.target.closest('button[data-act="del-item"]');
    if (del) {
        const gi = Number(del.getAttribute('data-g'));
        const ii = Number(del.getAttribute('data-i'));
        const g = ruleState.draft && ruleState.draft.groups[gi];
        if (!g || !g.items[ii]) return;
        if (!confirm('删掉这一条？\n\n' + g.name + ' · ' + g.items[ii].title)) return;
        g.items.splice(ii, 1);
        if (!g.items.length) ruleState.draft.groups.splice(gi, 1);   // 空组一起带走
        renderDraft();
        return;
    }

    const head = e.target.closest('[data-role="rg-toggle"]');
    if (!head) return;
    const box = head.parentElement;
    const g = ruleState.draft && ruleState.draft.groups[Number(box.getAttribute('data-g'))];
    if (!g) return;
    const key = g.dimension + '|' + g.name;
    const open = !box.classList.contains('open');
    box.classList.toggle('open', open);
    if (open) ruleState.open.add(key); else ruleState.open.delete(key);
});

$('rules-groups').addEventListener('change', e => {
    const input = e.target.closest('input.rs-input');
    if (!input) return;
    const gi = Number(input.getAttribute('data-g'));
    const ii = Number(input.getAttribute('data-i'));
    const g = ruleState.draft && ruleState.draft.groups[gi];
    if (!g || !g.items[ii]) return;

    const raw = input.value.trim();
    const v = raw === '' ? null : Number(raw);
    if (v != null && !isFinite(v)) {
        setMsg($('rules-save-msg'), 'err', '「' + g.items[ii].title + '」的分值不是数字：' + raw);
        return;
    }
    g.items[ii].score = v;
    input.parentElement.classList.toggle('need', v == null);
    if (v == null) input.placeholder = '?';
    renderDraftStat();
});

$('rules-toggle').addEventListener('click', () => {
    if (!ruleState.draft) return;
    ruleState.allOpen = !ruleState.allOpen;
    $('rules-toggle').textContent = ruleState.allOpen ? '全部收起' : '全部展开';
    renderDraft();
});

$('rules-discard').addEventListener('click', () => {
    if (!ruleState.draft) return;
    if (!confirm('放弃这次转换结果？当前配置不会变，可以重新传一份再转。')) return;
    ruleState.draft = null;
    ruleState.file = null;
    ruleState.open.clear();
    $('rules-file').value = '';
    $('rules-draft-card').style.display = 'none';
    $('rules-msg').textContent = '已放弃这次转换。当前规则没有任何变化。';
    updateRulesButtons();
});

$('rules-save').addEventListener('click', async () => {
    const d = ruleState.draft;
    if (!d) return;

    // 先自查一遍再发请求：让用户当场看到是哪几条，而不是等后端报错回来
    const bad = [];
    (d.groups || []).forEach(g => (g.items || []).forEach(i => {
        if (i.score == null) bad.push(g.name + ' · ' + i.title);
    }));
    if (bad.length) {
        setMsg($('rules-save-msg'), 'err', '还有 ' + bad.length + ' 条没有分值，补上或删掉：'
            + bad.slice(0, 3).join('、') + (bad.length > 3 ? ' 等' : ''));
        return;
    }
    const college = $('rules-college').value.trim();
    if (!college) {
        setMsg($('rules-save-msg'), 'err', '填一下学院名称——它写进配置，页面上靠它区分是哪份细则。');
        $('rules-college').focus();
        return;
    }

    $('rules-save').disabled = true;
    $('rules-discard').disabled = true;
    setMsg($('rules-save-msg'), '', '正在写入配置…');
    try {
        const r = await fetch('/api/rules/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ college, sourceFile: d.sourceFile, groups: d.groups })
        });
        const payload = await r.json().catch(() => ({}));
        if (!r.ok) {
            setMsg($('rules-save-msg'), 'err', payload.message || ('保存失败（HTTP ' + r.status + '）'));
            return;
        }
        const saved = payload.data || {};
        ruleState.draft = null;
        ruleState.file = null;
        ruleState.open.clear();
        $('rules-file').value = '';
        $('rules-draft-card').style.display = 'none';

        await loadRulesCurrent();
        await refreshStatus();          // 侧栏「规则」跟着变

        // 规则换了，之前那份分数是按旧细则算的。留着它比清掉更危险：
        // 用户会以为「算过了」，导出时才发现数字对不上。
        sc.report = null;
        exported = false;
        $('sc-result').innerHTML = '';
        $('ex-result').innerHTML = '';
        loadScoreMeta();
        syncSteps();

        setMsg($('rules-msg'), 'good', '已保存并生效：' + (saved.file || '')
            + '（' + (saved.groupCount || 0) + ' 组 / ' + (saved.itemCount || 0) + ' 条）。'
            + '旧配置已备份成 .bak-[时间] 放在同一目录。去 ⑥ 重新算一次分。');
    } catch (e) {
        setMsg($('rules-save-msg'), 'err', '请求失败：' + e);
    } finally {
        $('rules-save').disabled = false;
        $('rules-discard').disabled = false;
    }
});

// ── ④ 上传材料：多选 / 拖拽 → 队列 → 逐份识别 ──────────────────────

const ACCEPT_EXT = /\.(pdf|docx?|jpe?g|png|webp)$/i;

// 单份上限。PDF 会被逐页按 150dpi 渲染成图片，所以文件本身大不影响识别耗时；
// 放窄了反而会把「活动合影多、扫描件页数多」这类正常材料挡在外面。
const MAX_FILE_MB = 20;

// 同时识别几份。每份都要等一次模型响应，串行等于把等待时间乘上份数；
// 取 3 是因为再多容易撞上游限流（后端对 429 有一次自动重试兜底）。
const SCAN_CONCURRENCY = 3;

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

    const startedAll = Date.now();
    const lanes = Math.min(SCAN_CONCURRENCY, pending.length);
    let cursor = 0;
    let done = 0;
    let failed = 0;

    // 多个 lane 同时从队列里取任务：之前是一份一份排队，10 份就是 10 倍等待。
    // JS 是单线程，cursor++ 不会取到重复下标，不需要锁。
    const lane = async () => {
        for (;;) {
            const i = cursor++;
            if (i >= pending.length) return;
            const it = pending[i];
            it.status = 'running';
            renderQueue();
            setMsg($('msg'), '', '正在识别：剩余 ' + (pending.length - done) + ' 份'
                + (lanes > 1 ? '（同时 ' + lanes + ' 份，单份约 5 到 10 秒）' : '') + '，别关页面');
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
                failed++;
            }
            done++;
            renderQueue();
            renderReview();
        }
    };

    await Promise.all(Array.from({ length: lanes }, lane));

    state.running = false;
    $('btn').textContent = '开始识别';
    updateButtons();
    // 必须在这里再刷一次：最后一轮 renderQueue 时 running 还是 true，
    // 算分按钮被置灰后就再没人把它点亮（实测：批量扫完，「开始算分」一直是灰的）
    updateScoreHint();
    const secs = ((Date.now() - startedAll) / 1000).toFixed(1);
    const total = state.items.filter(x => x.status === 'done').reduce((s, x) => s + (x.data.totalTokens || 0), 0);
    const left = unresolvedCount();
    setMsg($('msg'), failed ? 'err' : (left ? '' : 'good'),
        '全部扫完，用时 ' + secs + ' 秒'
        + (total ? '，共 ' + total.toLocaleString() + ' tokens' : '') + '。'
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

// ── ⑤ 待确认清单 ────────────────────────────────────────────────────

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

// ── ⑥ 算分 ──────────────────────────────────────────────────────────
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
                // 已填过的值要带回来：更新细则后本函数会重跑一次，重建输入框时丢值
                // 会让用户以为「刚填的基础分被吃掉了」
                const cur = sc.inputs[inp.key];
                html += '<div class="field"><label>' + esc(label) + '</label>'
                    + '<input type="number" step="0.01" min="0" data-sc-key="' + esc(inp.key) + '"'
                    + ' value="' + (cur == null ? '' : esc(String(cur))) + '"'
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

// ── ⑦ 导出 ──────────────────────────────────────────────────────────
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
loadRulesCurrent();
loadScoreMeta();
