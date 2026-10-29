#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
综测材料抽取探针（零第三方依赖，只用标准库）

目的：验证「视觉模型能否从真实证书照片中稳定抽出结构化事实字段」。
      这是整个项目唯一的技术生死点——抽取不准，后面的规则引擎全都白搭。

用法（PowerShell / Git Bash 都行）：
    set LLM_API_KEY=sk-xxxx            # 或 export
    python probe.py --images "C:/Users/25090/Desktop/证书1.jpg" "C:/Users/25090/Desktop/证书2.jpg" \
                    --models qwen-vl-max glm-4v qwen-vl-plus

输出：
    out/raw/<model>/<image>.json   每次调用的原始返回（debug 用）
    out/report.md                  人看的对比表
    out/report.csv                 Excel 能打开的统计表
    out/summary.txt                一行结论

设计取舍：
- 不用官方 SDK：换厂商只需改 --base-url，脚本不用动
- 不用 requests：零安装，拿到就能跑
- 不做图片预处理：先看原始照片能考多少分，跑不通再上旋转矫正（别一上来就加复杂度）
"""
import argparse
import base64
import csv
import json
import mimetypes
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── 字段定义：事实字段必须全对才算 pass；弱判断字段只记录不算分 ──────────
FACT_FIELDS = ["doc_type", "person_name", "issuer", "date",
               "period_start", "period_end", "title"]
WEAK_FIELDS = ["achievement", "level_hint", "category_guess"]
OTHER_FIELDS = ["is_multi_person", "evidence_quote", "confidence"]

EXTRACT_PROMPT = """你是证明材料信息抽取器。只做事实抽取，不做分值判断、不做规则推断。

严格输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字，字段如下：

{
  "doc_type": "聘书|荣誉证书|竞赛获奖证书|技能证书|成绩单|志愿记录|其他",
  "person_name": "证书上的本人姓名",
  "is_multi_person": false,
  "issuer": "颁发或聘任单位全称",
  "date": "证书签发（落款）日期，YYYY-MM-DD，无法确定则 null",
  "period_start": "任期起始日 YYYY-MM-DD，仅聘书/任职类材料填写，其余一律 null",
  "period_end": "任期截止日 YYYY-MM-DD，仅聘书/任职类材料填写，其余一律 null",
  "title": "称号 / 职务 / 奖项名称",
  "achievement": "获奖等级或名次，没有则 null",
  "level_hint": "国家级|省级|市级|校级|院级|不确定",
  "category_guess": "德育|智育|体育|美育|劳育|null",
  "evidence_quote": "逐字摘录图片中的原文，禁止改写",
  "confidence": 0.0
}

规则：
1. 图片里没写的字段一律填 null，禁止推测或补全。
2. evidence_quote 必须逐字来自图片，不得改写、不得总结。
3. level_hint 只是弱判断，必须能由 issuer 推出；拿不准就填 "不确定"。
4. 如果图片根本不是证明材料（例如是一张流程图、截图、表格），doc_type 填 "其他"，
   其余字段能确定才填，不能确定就 null。
5. 不确定时降低 confidence，绝对不要编造。
6. date 是证书的签发/落款日期，不是任期日期。聘书上常有「落款日期」和「聘期」两组日期，
   必须分别填到 date 和 period_start / period_end，不要混为一谈。
7. is_multi_person 表示「图片中是否出现除本人以外的其他姓名」（共同作者、指导老师、
   其他获奖者都算），它用于判断是否需要框出本人姓名；拿不准时填 true。
"""


def encode_image(path: Path) -> str:
    mime = mimetypes.guess_type(str(path))[0] or "image/jpeg"
    b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:{mime};base64,{b64}"


def build_content(path: Path) -> list:
    return [
        {"type": "text", "text": EXTRACT_PROMPT},
        {"type": "image_url", "image_url": {"url": encode_image(path)}},
    ]


def extract_json(text: str) -> dict:
    """从模型返回里抠出 JSON。容忍 ```json 围栏和前后废话。"""
    if not text:
        raise ValueError("空返回")
    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fence:
        return json.loads(fence.group(1))
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("返回里找不到 JSON 对象")
    return json.loads(text[start:end + 1])


def call_api(base_url: str, api_key: str, model: str, content: list,
             timeout: int = 180, retries: int = 2) -> tuple:
    """返回 (解析后的 dict, 原始文本, 耗时, usage)。失败抛异常。"""
    url = base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,
        "max_tokens": 1500,
    }
    data = json.dumps(payload).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
        "User-Agent": "assessment-probe/0.1",
    }

    last_err = None
    for attempt in range(retries + 1):
        t0 = time.time()
        try:
            req = urllib.request.Request(url, data=data, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
            elapsed = time.time() - t0
            obj = json.loads(body)
            text = obj["choices"][0]["message"]["content"]
            usage = obj.get("usage", {})
            return extract_json(text), text, elapsed, usage
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")[:500]
            last_err = f"HTTP {e.code}: {detail}"
            if e.code in (429, 500, 502, 503, 504) and attempt < retries:
                wait = 2 ** attempt * 3
                print(f"    [{e.code}] 重试 {attempt + 1}/{retries}，等 {wait}s", flush=True)
                time.sleep(wait)
                continue
            raise RuntimeError(last_err)
        except Exception as e:  # 超时 / 解析失败
            last_err = f"{type(e).__name__}: {e}"
            if attempt < retries:
                time.sleep(2)
                continue
            raise RuntimeError(last_err)
    raise RuntimeError(last_err or "未知错误")


def norm(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v).strip()


# 自由文本字段（颁发单位、名称）不能精确相等：模型多写一个校区名就算错，不公平
LOOSE_FIELDS = {"issuer", "title"}
_PUNCT = str.maketrans("", "", " \t\r\n　《》〈〉“”\"'‘’()（）【】[]:：,，.。、-—_/")


def _loose(v) -> str:
    return norm(v).translate(_PUNCT).lower()


def _norm_date(v) -> str:
    """把 2025/6/28、2025年6月28日、2025-06-28 统一成 2025-06-28。
    模型输出格式不统一，不做归一化会把格式差异误判成"抽错了"。"""
    s = norm(v)
    if not s:
        return ""
    m = re.search(r"(\d{4})\D{0,3}(\d{1,2})\D{0,3}(\d{1,2})", s)
    if m:
        y, mo, d = m.groups()
        return f"{int(y):04d}-{int(mo):02d}-{int(d):02d}"
    m = re.search(r"(\d{4})\D{0,3}(\d{1,2})", s)
    if m:
        y, mo = m.groups()
        return f"{int(y):04d}-{int(mo):02d}"
    return s


def compare(gold: dict, pred: dict) -> list:
    """逐字段比对，返回 [(field, gold, pred, ok)]。ok=None 表示只展示不判分。"""
    rows = []
    for f in FACT_FIELDS + WEAK_FIELDS + OTHER_FIELDS:
        g, p = gold.get(f), pred.get(f)
        gs, ps = norm(g), norm(p)
        if f in ("confidence", "evidence_quote"):
            ok = None  # 只展示，人工看
        elif f in LOOSE_FIELDS:
            a, b = _loose(g), _loose(p)
            ok = (a == b) or bool(a and b and (a in b or b in a))
        elif f == "date" or f.startswith("period_"):
            ga, pa = _norm_date(g), _norm_date(p)
            ok = (ga == pa)
        else:
            ok = gs == ps
        rows.append((f, g, p, ok))
    return rows


def main():
    ap = argparse.ArgumentParser(description="综测材料抽取探针")
    ap.add_argument("--images", nargs="+", required=True, help="证书图片路径（可多张）")
    ap.add_argument("--models", nargs="+", required=True, help="模型名（可多个）")
    ap.add_argument("--base-url", default=os.environ.get(
        "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    ap.add_argument("--out", default="out", help="输出目录")
    ap.add_argument("--gold", default="gold.json", help="金标准 JSON（按文件名匹配）")
    args = ap.parse_args()

    api_key = os.environ.get("LLM_API_KEY", "").strip()
    if not api_key:
        print("错误：未设置环境变量 LLM_API_KEY", file=sys.stderr)
        return 2

    images = [Path(p) for p in args.images]
    for p in images:
        if not p.exists():
            print(f"错误：文件不存在 {p}", file=sys.stderr)
            return 2
        size_mb = p.stat().st_size / 1024 / 1024
        if size_mb > 5:
            print(f"警告：{p.name} 有 {size_mb:.1f}MB，base64 后可能被接口拒绝", flush=True)

    gold_all = {}
    gold_path = Path(args.gold)
    if gold_path.exists():
        gold_all = json.loads(gold_path.read_text(encoding="utf-8"))
    else:
        print(f"提示：没找到 {gold_path}，只输出模型结果、不做比对", flush=True)

    out_dir = Path(args.out)
    (out_dir / "raw").mkdir(parents=True, exist_ok=True)

    results = []  # 每行一条 (model, image, fact_hit, fact_total, elapsed, usage, error)
    details = []  # (model, image, rows, pred)

    for model in args.models:
        for img in images:
            print(f"[{model}] {img.name} ...", flush=True)
            try:
                content = build_content(img)
                pred, raw, elapsed, usage = call_api(
                    args.base_url, api_key, model, content)
                error = ""
            except Exception as e:
                pred, raw, elapsed, usage, error = {}, "", 0.0, {}, str(e)
                print(f"    失败：{error}", flush=True)

            raw_dir = out_dir / "raw" / model
            raw_dir.mkdir(parents=True, exist_ok=True)
            (raw_dir / f"{img.stem}.json").write_text(
                json.dumps({"parsed": pred, "raw": raw, "error": error},
                           ensure_ascii=False, indent=2),
                encoding="utf-8")

            gold = gold_all.get(img.name, {})
            rows = compare(gold, pred) if gold else []
            fact_hit = sum(1 for f, g, p, ok in rows if f in FACT_FIELDS and ok) if gold else 0

            results.append({
                "model": model,
                "image": img.name,
                "fact_hit": fact_hit,
                "fact_total": len(FACT_FIELDS) if gold else 0,
                "elapsed": round(elapsed, 2),
                "tokens": usage.get("total_tokens", ""),
                "error": error,
            })
            details.append((model, img.name, rows, pred))

    # ── 写 CSV ────────────────────────────────────────────────
    with open(out_dir / "report.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(results[0].keys()))
        w.writeheader()
        w.writerows(results)

    # ── 写 Markdown ──────────────────────────────────────────
    lines = ["# 抽取探针报告", "",
             f"- 接口：`{args.base_url}`",
             f"- 模型：{', '.join(args.models)}",
             f"- 图片：{len(images)} 张",
             f"- 生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
             "",
             "## 总览", "",
             "| 模型 | 图片 | 事实字段命中 | 准确率 | 耗时(s) | tokens | 错误 |",
             "|---|---|---|---|---|---|---|"]
    for r in results:
        if r["fact_total"]:
            acc = f"{r['fact_hit']}/{r['fact_total']} = {r['fact_hit']/r['fact_total']*100:.0f}%"
        else:
            acc = "无金标准"
        lines.append(f"| {r['model']} | {r['image']} | {r['fact_hit']} | {acc} "
                     f"| {r['elapsed']} | {r['tokens']} | {r['error'][:60]} |")

    lines += ["", "## 逐字段明细", "",
              "> 事实字段（必须全对）：" + "、".join(FACT_FIELDS),
              "> 弱判断字段（只记录）：" + "、".join(WEAK_FIELDS),
              ""]
    for model, image, rows, pred in details:
        lines.append(f"### {model} / {image}")
        if not rows:
            lines.append("")
            lines.append("```json")
            lines.append(json.dumps(pred, ensure_ascii=False, indent=2))
            lines.append("```")
            lines.append("")
            continue
        lines += ["", "| 字段 | 金标准 | 模型输出 | 结果 |", "|---|---|---|---|"]
        for f, g, p, ok in rows:
            mark = {True: "OK", False: "**差异**", None: "-"}[ok]
            lines.append(f"| {f} | {norm(g)[:80]} | {norm(p)[:80]} | {mark} |")
        lines.append("")

    (out_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")

    # ── 一行结论 ─────────────────────────────────────────────
    total_hit = sum(r["fact_hit"] for r in results)
    total_max = sum(r["fact_total"] for r in results)
    summary = (f"事实字段命中 {total_hit}/{total_max}"
               f"（{total_hit / total_max * 100:.0f}%）" if total_max else "无金标准，未统计")
    (out_dir / "summary.txt").write_text(summary + "\n", encoding="utf-8")

    print("\n" + "=" * 50)
    print(summary)
    print(f"报告：{(out_dir / 'report.md').resolve()}")
    print("=" * 50)
    return 0


if __name__ == "__main__":
    sys.exit(main())
