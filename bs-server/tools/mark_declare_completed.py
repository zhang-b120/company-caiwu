"""
mark_declare_completed.py
-------------------------
批量切换任务工作台「已完成 / 未完成」开关（fxy_financial_task_period.declare_completed）。

输入: 一个 .xlsx，至少含「公司名」「税号」两列（列名可用 --name-col / --code-col 覆盖）。
按税号匹配 fxy_financial_account_sets.credit_code → account_set_id，
然后对 (account_set_id, --period) 把 declare_completed 置 1（默认）或 0（--off）。

为安全起见默认是 dry-run，只打印计划；加 --apply 才真正写库。

  python tools/mark_declare_completed.py --excel tmp/不需要查年报.xlsx --period 2026-04
  python tools/mark_declare_completed.py --excel tmp/不需要查年报.xlsx --period 2026-04 --apply
  python tools/mark_declare_completed.py --excel xxx.xlsx --period 2026-04 --off --apply
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter

try:
    import openpyxl
except ImportError:
    sys.exit("缺少 openpyxl：  pip install openpyxl")

try:
    import pymysql
except ImportError:
    sys.exit("缺少 pymysql：  pip install pymysql")


DEFAULT_DB = dict(
    host="150.139.141.112",
    port=3306,
    user="qy",
    password="qykj@123",
    database="financial",
)

TABLE_PERIOD = "fxy_financial_task_period"
TABLE_ACCOUNT_SETS = "fxy_financial_account_sets"


def parse_args():
    p = argparse.ArgumentParser(
        description="批量切换任务工作台 declare_completed",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--excel", required=True, help="xlsx 路径")
    p.add_argument("--period", required=True, help="账期，格式 YYYY-MM，例如 2026-04")
    p.add_argument("--name-col", default="公司名", help='Excel 公司名列名（默认 "公司名"）')
    p.add_argument("--code-col", default="税号", help='Excel 税号列名（默认 "税号"）')
    p.add_argument("--sheet", default=None, help="sheet 名称，默认第一张")
    p.add_argument("--off", action="store_true",
                   help="把 declare_completed 置 0（取消已完成）。默认是置 1")
    p.add_argument("--apply", action="store_true",
                   help="真正写库；不加该参数则只 dry-run")
    p.add_argument("--create-missing", action="store_true",
                   help="该账期无 task_period 时插入一条新记录；默认仅更新已有")
    p.add_argument("--no-strip-quote", action="store_true",
                   help="不去掉 Excel 文本前缀的单引号（默认会去掉）")
    p.add_argument("--show", type=int, default=10,
                   help="每类抽样打印的最大行数（默认 10）")
    p.add_argument("--host", default=DEFAULT_DB["host"])
    p.add_argument("--port", type=int, default=DEFAULT_DB["port"])
    p.add_argument("--user", default=DEFAULT_DB["user"])
    p.add_argument("--password", default=DEFAULT_DB["password"])
    p.add_argument("--database", default=DEFAULT_DB["database"])
    return p.parse_args()


def read_excel(path: str, sheet: str | None, name_col: str, code_col: str,
               strip_quote: bool) -> list[dict]:
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    ws = wb[sheet] if sheet else wb.worksheets[0]
    rows = ws.iter_rows(values_only=True)
    try:
        header = [(str(h).strip() if h is not None else "") for h in next(rows)]
    except StopIteration:
        sys.exit("Excel 为空")
    try:
        ni = header.index(name_col)
    except ValueError:
        sys.exit(f"未在 Excel 表头找到列 {name_col!r}；实际表头: {header}")
    try:
        ci = header.index(code_col)
    except ValueError:
        sys.exit(f"未在 Excel 表头找到列 {code_col!r}；实际表头: {header}")

    items = []
    for r in rows:
        name_v = r[ni] if ni < len(r) else None
        code_v = r[ci] if ci < len(r) else None
        name = str(name_v).strip() if name_v is not None else ""
        code_raw = str(code_v).strip() if code_v is not None else ""
        code = code_raw.lstrip("'") if strip_quote else code_raw
        if not name and not code:
            continue
        items.append({"name": name, "code_raw": code_raw, "code": code})
    return items


def main():
    args = parse_args()
    target = 0 if args.off else 1
    action = "未完成（置 0）" if args.off else "已完成（置 1）"

    print(f"=== 任务工作台批量{action} ===")
    print(f"Excel  : {args.excel}")
    print(f"账期   : {args.period}")
    print(f"模式   : {'APPLY (写库)' if args.apply else 'DRY-RUN (不写库)'}")
    print(f"DB     : {args.user}@{args.host}:{args.port}/{args.database}")
    print()

    items = read_excel(args.excel, args.sheet, args.name_col, args.code_col,
                       strip_quote=not args.no_strip_quote)
    codes = [it["code"] for it in items if it["code"]]
    stripped = [it for it in items if it["code_raw"] != it["code"]]
    no_code = [it for it in items if not it["code"]]
    dups = [c for c, n in Counter(codes).items() if n > 1]

    print(f"[Excel] 读到行数            : {len(items)}")
    print(f"[Excel] 有税号              : {len(codes)}")
    print(f"[Excel] 唯一税号            : {len(set(codes))}")
    if stripped:
        print(f"[Excel] 已剥离前导单引号    : {len(stripped)} 行")
    if no_code:
        print(f"[Excel] 缺税号（忽略）      : {len(no_code)} 行")
        for it in no_code[: args.show]:
            print(f"        - {it['name']}")
    if dups:
        print(f"[Excel] 重复税号            : {len(dups)} 个（按去重处理）")

    # 长度异常提示（正常 18 位）
    abnormal = sorted({c for c in codes if len(c) != 18})
    if abnormal:
        print(f"[Excel] 长度非 18 位的税号  : {len(abnormal)}")
        for c in abnormal[: args.show]:
            print(f"        - len={len(c)} {c}")

    uniq = sorted(set(codes))
    if not uniq:
        print("\n没有可处理的税号，退出。")
        return

    conn = pymysql.connect(
        host=args.host, port=args.port, user=args.user,
        password=args.password, database=args.database,
        charset="utf8mb4", autocommit=False,
    )
    try:
        with conn.cursor() as cur:
            fmt = ",".join(["%s"] * len(uniq))
            cur.execute(
                f"SELECT id, company_name, credit_code FROM {TABLE_ACCOUNT_SETS} "
                f"WHERE credit_code IN ({fmt})", uniq)
            acc_rows = cur.fetchall()
            in_db = {r[2] for r in acc_rows}
            account_ids = sorted({r[0] for r in acc_rows})
            print(f"\n[DB] account_sets 命中    : {len(in_db)}/{len(uniq)}")

            miss_codes = [c for c in uniq if c not in in_db]
            if miss_codes:
                print(f"[DB] 未找到的税号          : {len(miss_codes)}")
                name_by_code = {it["code"]: it["name"] for it in items if it["code"]}
                for c in miss_codes[: args.show]:
                    print(f"     - {c}  ({name_by_code.get(c, '')})")
                if len(miss_codes) > args.show:
                    print(f"     ...还有 {len(miss_codes) - args.show} 个未列出")

            if not account_ids:
                print("\n没有命中任何账套，退出。")
                return

            fmt2 = ",".join(["%s"] * len(account_ids))
            cur.execute(
                f"SELECT id, account_set_id, declare_completed FROM {TABLE_PERIOD} "
                f"WHERE period=%s AND account_set_id IN ({fmt2})",
                [args.period, *account_ids])
            tp_rows = cur.fetchall()
            # declare_completed 在 MySQL 里是 BIT(1)，pymysql 返回 b'\x00' / b'\x01'
            def _bit_to_int(v) -> int:
                if v is None:
                    return 0
                if isinstance(v, (bytes, bytearray)):
                    return 1 if any(v) else 0
                return int(bool(v))
            already = [r for r in tp_rows if _bit_to_int(r[2]) == target]
            to_change = [r for r in tp_rows if _bit_to_int(r[2]) != target]
            existing_ids = {r[1] for r in tp_rows}
            no_period_ids = [aid for aid in account_ids if aid not in existing_ids]

            print(f"\n[DB] task_period @ {args.period}:")
            print(f"     - 已存在记录数         : {len(tp_rows)}/{len(account_ids)}")
            print(f"     - 已经是目标值        : {len(already)}")
            print(f"     - 需要翻转            : {len(to_change)}")
            print(f"     - 该账期无 task_period: {len(no_period_ids)}"
                  + ("（将插入）" if args.create_missing else "（跳过）"))

            if no_period_ids and not args.create_missing:
                name_by_id = {r[0]: r[1] for r in acc_rows}
                for aid in no_period_ids[: args.show]:
                    print(f"        - account_set_id={aid}  {name_by_id.get(aid, '')}")
                if len(no_period_ids) > args.show:
                    print(f"        ...还有 {len(no_period_ids) - args.show} 个未列出")

            total = len(to_change) + (len(no_period_ids) if args.create_missing else 0)
            print(f"\n=== 计划写库 {total} 行（UPDATE={len(to_change)}, "
                  f"INSERT={len(no_period_ids) if args.create_missing else 0}）===")

            if not args.apply:
                print("dry-run 完成，未提交。加 --apply 才真正写库。")
                return

            updated = inserted = 0
            if to_change:
                update_ids = [r[0] for r in to_change]
                fmt3 = ",".join(["%s"] * len(update_ids))
                cur.execute(
                    f"UPDATE {TABLE_PERIOD} "
                    f"SET declare_completed=%s, updated_at=NOW() WHERE id IN ({fmt3})",
                    [target, *update_ids])
                updated = cur.rowcount

            if args.create_missing and no_period_ids:
                # 字段默认值与 entity 对齐（step_annual_report=na，其余 pending）
                values = [
                    (aid, args.period,
                     "pending", "pending", "pending", "pending",
                     "pending", "pending", "pending", "na", "pending",
                     target)
                    for aid in no_period_ids
                ]
                cur.executemany(
                    f"INSERT INTO {TABLE_PERIOD} "
                    "(account_set_id, period, "
                    " step_verify, step_init, step_data, step_bookkeep, "
                    " step_tax_national, step_tax_iit, step_payment, "
                    " step_annual_report, step_tax_annual, "
                    " declare_completed, created_at, updated_at) "
                    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),NOW())",
                    values)
                inserted = cur.rowcount

            conn.commit()
            print(f"已提交。UPDATE={updated} 行，INSERT={inserted} 行。")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
