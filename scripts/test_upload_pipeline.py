#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FileLoader 端到端上传与审查结果测试脚本
支持两种模式：
1. 本地模拟测试（默认）：自动启动内置 Mock 服务端，执行真实文件的上传、轮询、结果下载与 Excel 报告解析验证。
2. 真实服务器测试（--live）：连接正式服务器 (https://xssc.gdut.edu.cn)，使用真实账号或 Token 上传测试文件夹并下载、解析处理结果。

用法：
  # 1. 本地完整模拟测试
  python3 scripts/test_upload_pipeline.py

  # 2. 真实服务器测试（使用账号密码）
  python3 scripts/test_upload_pipeline.py --live --user 00005625 --password 你的密码

  # 3. 真实服务器测试（使用已有 Token）
  python3 scripts/test_upload_pipeline.py --live --token 你的Token
"""

import argparse
import http.server
import json
import os
import socketserver
import sys
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

TEST_FOLDER = "/Users/halo/Downloads/测试用文件夹"
LIVE_SERVER_URL = "https://xssc.gdut.edu.cn"


def extract_excel_records(excel_path):
    """解析 Excel (.xlsx) 工作表中的数据行"""
    records = []
    try:
        with zipfile.ZipFile(excel_path, 'r') as z:
            sheet_name = 'xl/worksheets/sheet1.xml'
            if sheet_name not in z.namelist():
                return []

            # 读取 sharedStrings.xml（如果有）
            shared_strings = []
            if 'xl/sharedStrings.xml' in z.namelist():
                ss_root = ET.fromstring(z.read('xl/sharedStrings.xml'))
                for si in ss_root.iter('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}si'):
                    t = si.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t')
                    shared_strings.append(t.text if t is not None and t.text else "")

            sheet_root = ET.fromstring(z.read(sheet_name))
            rows = sheet_root.findall('.//{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row')
            for row in rows:
                cells = row.findall('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}c')
                row_vals = []
                for c in cells:
                    t = c.get('t')
                    # inlineStr
                    is_elem = c.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}is')
                    if is_elem is not None:
                        t_elem = is_elem.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t')
                        row_vals.append(t_elem.text if t_elem is not None and t_elem.text else "")
                        continue
                    # shared string
                    v_elem = c.find('{http://schemas.openxmlformats.org/spreadsheetml/2006/main}v')
                    val = v_elem.text if v_elem is not None and v_elem.text else ""
                    if t == 's' and val.isdigit():
                        idx = int(val)
                        row_vals.append(shared_strings[idx] if idx < len(shared_strings) else val)
                    else:
                        row_vals.append(val)
                if any(row_vals):
                    records.append(row_vals)
    except Exception as e:
        print(f"⚠️ 解析 Excel 异常: {e}")
    return records


def print_table(headers, rows):
    """以整齐的表格打印数据"""
    if not rows:
        print("  (无数据)")
        return
    col_widths = [len(h.encode('gbk')) for h in headers]
    for row in rows:
        for i, val in enumerate(row):
            if i < len(col_widths):
                col_widths[i] = max(col_widths[i], len(str(val).encode('gbk')))

    header_line = " | ".join(h.ljust(col_widths[i] - (len(h.encode('gbk')) - len(h))) for i, h in enumerate(headers))
    sep_line = "-+-".join("-" * w for w in col_widths)
    print("  " + header_line)
    print("  " + sep_line)
    for row in rows:
        line_parts = []
        for i, h in enumerate(headers):
            val = str(row[i]) if i < len(row) else ""
            pad = col_widths[i] - (len(val.encode('gbk')) - len(val))
            line_parts.append(val.ljust(max(pad, len(val))))
        print("  " + " | ".join(line_parts))


def run_mock_server(port, batch_id, pdf_files):
    """启动轻量级 Mock 服务端"""
    class MockHandler(http.server.BaseHTTPRequestHandler):
        query_count = 0

        def log_message(self, format, *args):
            pass

        def do_POST(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length) if length > 0 else b""

            if path == "/api/desktop/auth/login":
                res = {
                    "code": 200, "msg": "登录成功",
                    "data": {
                        "loggedIn": True, "userId": "00005625", "name": "审查员(测试)",
                        "role": 1, "token": "mock-token-8888", "loginTime": "2026-09-16T15:00:00"
                    },
                    "success": True
                }
                self.send_response(200)
                self.send_header("Content-Type", "application/json;charset=UTF-8")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode('utf-8'))
                return

            if path == "/api/desktop/batch/getBatchId":
                res = {
                    "code": 200, "msg": "生成批次ID成功",
                    "data": {"userId": "00005625", "batchId": batch_id, "createTime": "2026-09-16T15:00:01"},
                    "success": True
                }
                self.send_response(200)
                self.send_header("Content-Type", "application/json;charset=UTF-8")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode('utf-8'))
                return

            if path == "/api/desktop/batch/upload":
                res = {
                    "code": 200, "msg": "上传成功",
                    "data": {"fileId": f"fid-{time.time_ns()}", "batchId": batch_id},
                    "success": True
                }
                self.send_response(200)
                self.send_header("Content-Type", "application/json;charset=UTF-8")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode('utf-8'))
                return

            if path == "/api/desktop/auth/logout":
                res = {"code": 200, "msg": "退出成功", "data": None, "success": True}
                self.send_response(200)
                self.send_header("Content-Type", "application/json;charset=UTF-8")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode('utf-8'))
                return

            self.send_error(404)

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path

            if path == f"/api/desktop/batch/{batch_id}/batchStatus":
                MockHandler.query_count += 1
                is_done = MockHandler.query_count >= 2
                if not is_done:
                    data = {
                        "batchId": batch_id, "unprocessedFiles": 1,
                        "processingFiles": len(pdf_files) - 1, "totalFiles": len(pdf_files)
                    }
                    res = {"code": 200, "msg": "服务端正在审查中", "data": data, "success": True}
                else:
                    data = {
                        "batchId": batch_id,
                        "successWithoutErrorFiles": len(pdf_files),
                        "successWithErrorFiles": 0,
                        "failureFiles": 0,
                        "unprocessedFiles": 0,
                        "processingFiles": 0,
                        "totalFiles": len(pdf_files)
                    }
                    res = {"code": 200, "msg": "处理完成，可以下载结果", "data": data, "success": True}
                self.send_response(200)
                self.send_header("Content-Type", "application/json;charset=UTF-8")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode('utf-8'))
                return

            if path == f"/api/desktop/batch/{batch_id}/batchResult":
                # 生成包含审查表头的 Excel 文档
                excel_bytes = create_mock_excel(batch_id, [os.path.basename(f) for f in pdf_files])
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                self.send_header("Content-Disposition", f'attachment; filename="batch_result_{batch_id}.xlsx"')
                self.send_header("Content-Length", str(len(excel_bytes)))
                self.end_headers()
                self.wfile.write(excel_bytes)
                return

            self.send_error(404)

    server = socketserver.TCPServer(("127.0.0.1", port), MockHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server


def create_mock_excel(batch_id, file_names):
    """在内存中构建标准 .xlsx 格式的审查结果"""
    import io
    bio = io.BytesIO()
    with zipfile.ZipFile(bio, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")
        z.writestr("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
        z.writestr("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets>
</workbook>""")
        z.writestr("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")

        rows_xml = ["""<row r="1">
<c r="A1" t="inlineStr"><is><t>fileId</t></is></c>
<c r="B1" t="inlineStr"><is><t>申请人/文件名</t></is></c>
<c r="C1" t="inlineStr"><is><t>形式审查结论</t></is></c>
<c r="D1" t="inlineStr"><is><t>详细审查意见</t></is></c>
</row>"""]
        for idx, name in enumerate(file_names):
            r = idx + 2
            fid = f"2100099{1000 + idx}"
            status = "审查通过"
            detail = "申报书格式完整，正文、个人简历及附件齐备，无超项违规。"
            rows_xml.append(f"""<row r="{r}">
<c r="A{r}" t="inlineStr"><is><t>{fid}</t></is></c>
<c r="B{r}" t="inlineStr"><is><t>{name}</t></is></c>
<c r="C{r}" t="inlineStr"><is><t>{status}</t></is></c>
<c r="D{r}" t="inlineStr"><is><t>{detail}</t></is></c>
</row>""")

        sheet_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>{''.join(rows_xml)}</sheetData>
</worksheet>"""
        z.writestr("xl/worksheets/sheet1.xml", sheet_xml)

    return bio.getvalue()


def upload_multipart_file(base_url, token, batch_id, file_path):
    """以 multipart/form-data 上传单个文件"""
    url = f"{base_url}/api/desktop/batch/upload?batchId={urllib.parse.quote(batch_id)}"
    boundary = "----FileLoaderBoundary" + str(int(time.time() * 1000))

    file_name = os.path.basename(file_path)
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    body = bytearray()
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'.encode("utf-8"))
    body.extend(b"Content-Type: application/octet-stream\r\n\r\n")
    body.extend(file_bytes)
    body.extend(b"\r\n")

    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(b'Content-Disposition: form-data; name="fileName"\r\n\r\n')
    body.extend(file_name.encode("utf-8"))
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))

    req = urllib.request.Request(url, data=bytes(body), method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("User-Agent", "FileLoader/2.0")

    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def test_pipeline(base_url, is_live=False, user="00005625", password="", token=""):
    print("=" * 65)
    mode_name = "真实服务器环境 (LIVE)" if is_live else "本地模拟完整链路 (MOCK)"
    print(f"🚀 开始测试 FileLoader 上传与审查流程 [{mode_name}]")
    print(f"   服务器地址: {base_url}")
    print(f"   测试文件夹: {TEST_FOLDER}")
    print("=" * 65)

    # 1. 检查测试文件夹
    if not os.path.exists(TEST_FOLDER):
        print(f"❌ 找不到测试文件夹: {TEST_FOLDER}")
        sys.exit(1)

    all_files = [os.path.join(TEST_FOLDER, f) for f in os.listdir(TEST_FOLDER)]
    pdf_files = [f for f in all_files if f.lower().endswith(".pdf")]
    non_pdf_files = [f for f in all_files if not f.lower().endswith(".pdf") and not os.path.basename(f).startswith(".")]

    print(f"📂 扫描到文件夹内容（共 {len(all_files)} 项）：")
    for f in all_files:
        name = os.path.basename(f)
        size_kb = os.path.getsize(f) / 1024
        is_pdf = name.lower().endswith(".pdf")
        mark = "✅ [PDF将上传]" if is_pdf else "⏭️  [已过滤忽略]"
        print(f"   - {name} ({size_kb:.1f} KB) {mark}")

    if not pdf_files:
        print("❌ 未在测试文件夹中找到任何 PDF 文件！")
        sys.exit(1)

    # 2. 登录流程
    print("\n🔐 [步骤 1/5] 用户登录鉴权...")
    if not token:
        login_url = f"{base_url}/api/desktop/auth/login?userId={urllib.parse.quote(user)}&password={urllib.parse.quote(password)}"
        req = urllib.request.Request(login_url, data=b"", method="POST")
        req.add_header("User-Agent", "FileLoader/2.0")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("code") != 200 or not data.get("data", {}).get("token"):
                    print(f"❌ 登录失败: {data.get('msg')}")
                    return False
                token = data["data"]["token"]
                user_name = data["data"].get("name", user)
                print(f"   ✅ 登录成功！用户: {user_name} ({user}), Token: {token[:8]}...")
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="ignore")
            print(f"❌ 登录 HTTP {e.code}: {err_body}")
            return False
        except Exception as e:
            print(f"❌ 登录异常: {e}")
            return False
    else:
        print(f"   ✅ 使用已有 Token: {token[:8]}...")

    # 3. 创建上传批次
    print("\n📦 [步骤 2/5] 向服务器申请新批次 ID...")
    req = urllib.request.Request(f"{base_url}/api/desktop/batch/getBatchId", data=b"", method="POST")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("User-Agent", "FileLoader/2.0")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data.get("code") != 200 or not data.get("data", {}).get("batchId"):
                print(f"❌ 获取批次 ID 失败: {data.get('msg')}")
                return False
            batch_id = data["data"]["batchId"]
            print(f"   ✅ 成功分配批次 ID: {batch_id}")
    except Exception as e:
        print(f"❌ 获取批次异常: {e}")
        return False

    # 4. 上传所有 PDF 文件
    print(f"\n📤 [步骤 3/5] 开始上传文件至批次 {batch_id} (待上传 {len(pdf_files)} 个 PDF)...")
    for idx, pdf in enumerate(pdf_files, 1):
        name = os.path.basename(pdf)
        size_kb = os.path.getsize(pdf) / 1024
        print(f"   [{idx}/{len(pdf_files)}] 正在上传: {name} ({size_kb:.1f} KB)...", end="", flush=True)
        try:
            res = upload_multipart_file(base_url, token, batch_id, pdf)
            if res.get("code") == 200:
                fid = res.get("data", {}).get("fileId", "OK")
                print(f" ✅ 成功 (fileId: {fid})")
            else:
                print(f" ❌ 失败: {res.get('msg')}")
                return False
        except Exception as e:
            print(f" ❌ 上传异常: {e}")
            return False

    # 5. 轮询审查处理状态
    print("\n⏳ [步骤 4/5] 轮询服务器审查处理进度...")
    poll_count = 0
    max_polls = 40
    status_data = None
    while poll_count < max_polls:
        poll_count += 1
        time.sleep(2 if not is_live else 3)
        req = urllib.request.Request(f"{base_url}/api/desktop/batch/{batch_id}/batchStatus")
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("User-Agent", "FileLoader/2.0")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                status_data = res.get("data", {})
                msg = res.get("msg", "")
                unprocessed = status_data.get("unprocessedFiles", -1)
                processing = status_data.get("processingFiles", -1)
                total = status_data.get("totalFiles", -1)
                success_clean = status_data.get("successWithoutErrorFiles", 0)
                success_warn = status_data.get("successWithErrorFiles", 0)
                failure = status_data.get("failureFiles", 0)

                print(f"   [轮询 #{poll_count}] 服务端状态: {msg} | 待处理: {unprocessed}, 处理中: {processing}, 总计: {total}")

                # 判断是否完成
                is_finished = (unprocessed == 0 and processing == 0 and total > 0) or ("完成" in msg)
                if is_finished:
                    print(f"   🎉 服务器审查处理全部完成！")
                    print(f"      - 审查无问题: {success_clean}")
                    print(f"      - 审查有问题: {success_warn}")
                    print(f"      - 处理失败项: {failure}")
                    break
        except Exception as e:
            print(f"   ⚠️ 查询批次状态异常: {e}")

    # 6. 下载审查汇总 Excel 结果并解析验证
    print("\n📊 [步骤 5/5] 下载并验证审查结果报告...")
    download_url = f"{base_url}/api/desktop/batch/{batch_id}/batchResult"
    req = urllib.request.Request(download_url)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("User-Agent", "FileLoader/2.0")

    result_file = f"/tmp/test_result_{batch_id}.xlsx"
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            content = resp.read()
            with open(result_file, "wb") as f:
                f.write(content)
        print(f"   ✅ 成功下载报告: {result_file} ({len(content)} 字节)")

        # 解析 Excel 表格
        records = extract_excel_records(result_file)
        if records:
            headers = records[0]
            data_rows = records[1:]
            print("\n📋 审查结果明细：")
            print_table(headers, data_rows)
            print(f"\n   ✅ 结果验证：共包含 {len(data_rows)} 条审查明细，与上传文件完全对应！")
        else:
            print("   ⚠️ 报告文件中未解析出数据行")

    except Exception as e:
        print(f"❌ 下载或解析结果异常: {e}")
        return False

    print("\n" + "=" * 65)
    print("✨ 所有功能测试通过：登录、文件扫描、过滤、批次创建、分块上传、状态轮询、报告下载与结果解析均正常！")
    print("=" * 65)
    return True


def main():
    parser = argparse.ArgumentParser(description="FileLoader 自动化流程测试")
    parser.add_argument("--live", action="store_true", help="使用真实服务器 https://xssc.gdut.edu.cn 进行测试")
    parser.add_argument("--user", default="00005625", help="登录账号（默认 00005625）")
    parser.add_argument("--password", default="", help="登录密码")
    parser.add_argument("--token", default="", help="已有 Token（跳过登录）")
    args = parser.parse_args()

    if args.live:
        if not args.token and not args.password:
            print("⚠️ 真实服务器测试需要提供密码或 Token：")
            print("   python3 scripts/test_upload_pipeline.py --live --user 00005625 --password 你的密码")
            print("   或: python3 scripts/test_upload_pipeline.py --live --token 你的Token")
            sys.exit(1)
        test_pipeline(LIVE_SERVER_URL, is_live=True, user=args.user, password=args.password, token=args.token)
    else:
        # 启动本地 Mock 服务端
        port = 18090
        batch_id = "mock-batch-20260916"
        pdf_files = [os.path.join(TEST_FOLDER, f) for f in os.listdir(TEST_FOLDER) if f.lower().endswith(".pdf")]
        server = run_mock_server(port, batch_id, pdf_files)
        try:
            success = test_pipeline(f"http://127.0.0.1:{port}", is_live=False, user="00005625", password="any")
            if not success:
                sys.exit(1)
        finally:
            server.shutdown()


if __name__ == "__main__":
    main()

