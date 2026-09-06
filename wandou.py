#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
豌豆荚应用搜索和下载工具
Wandoujia App Search & Download Tool

功能：
  - 搜索豌豆荚上的 Android 应用
  - 查看应用详细信息
  - 下载 APK 文件
"""

import os
import sys
import json
import argparse
from urllib.parse import quote, urlparse, parse_qs

import requests
from bs4 import BeautifulSoup


class WandoujiaClient:
    """豌豆荚客户端"""

    SEARCH_API = "https://www.wandoujia.com/wdjweb/api/search/more"
    DOWNLOAD_URL_API = "https://server-m.pp.cn/download/url"
    DOWNLOAD_APK_API = "https://server-m.pp.cn/download/apk"
    DETAIL_URL = "https://www.wandoujia.com/apps/{app_id}"

    DEFAULT_HEADERS = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/html, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }

    def __init__(self):
        self.session = requests.Session()
        self.session.headers.update(self.DEFAULT_HEADERS)

    def search(self, keyword, page=1):
        """
        搜索应用

        Args:
            keyword: 搜索关键词
            page: 页码（从1开始）

        Returns:
            list: 应用列表，每个应用是一个字典
        """
        params = {
            "key": keyword,
            "page": page,
        }

        try:
            resp = self.session.get(self.SEARCH_API, params=params, timeout=15)
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, json.JSONDecodeError) as e:
            print(f"[错误] 搜索请求失败: {e}", file=sys.stderr)
            return []

        if data.get("state", {}).get("code") != 2000000:
            msg = data.get("state", {}).get("msg", "未知错误")
            print(f"[错误] 搜索失败: {msg}", file=sys.stderr)
            return []

        content_html = data.get("data", {}).get("content", "")
        if not content_html.strip():
            return []

        return self._parse_search_results(content_html)

    def _parse_search_results(self, html_content):
        """解析搜索结果 HTML"""
        apps = []
        soup = BeautifulSoup(html_content, "html.parser")
        items = soup.find_all("li", class_="search-item")

        for item in items:
            app = {}

            # 应用名称和链接
            name_tag = item.find("a", class_="name")
            if name_tag:
                app["name"] = name_tag.get_text(strip=True)
                app["detail_url"] = name_tag.get("href", "")

            # 应用图标
            icon_tag = item.find("img", class_="icon")
            if icon_tag:
                app["icon"] = icon_tag.get("src", "")

            # 安装人数
            install_tag = item.find("span", class_="install-count")
            if not install_tag:
                meta = item.find("div", class_="meta")
                if meta:
                    install_tag = meta.find("span")
            if install_tag:
                app["install_count"] = install_tag.get_text(strip=True)

            # 应用简介
            comment_tag = item.find("div", class_="comment")
            if comment_tag:
                app["description"] = comment_tag.get_text(strip=True)

            # data 属性中的详细信息
            detail_btn = item.find("a", class_="detail-check-btn")
            if detail_btn:
                app["app_id"] = detail_btn.get("data-app-id", "")
                app["app_vid"] = detail_btn.get("data-app-vid", "")
                app["package_name"] = detail_btn.get("data-app-pname", "")
                app["version_name"] = detail_btn.get("data-app-vname", "")
                app["version_code"] = detail_btn.get("data-app-vcode", "")
                app["icon_url"] = detail_btn.get("data-app-icon", "")
                app["category_id"] = detail_btn.get("data-app-categoryid", "")

            apps.append(app)

        return apps

    def get_app_detail(self, app_id_or_package):
        """
        获取应用详情

        Args:
            app_id_or_package: 应用ID或包名

        Returns:
            dict: 应用详情
        """
        url = self.DETAIL_URL.format(app_id=app_id_or_package)

        try:
            resp = self.session.get(url, timeout=15)
            resp.raise_for_status()
        except requests.RequestException as e:
            print(f"[错误] 获取应用详情失败: {e}", file=sys.stderr)
            return {}

        return self._parse_detail_page(resp.text, url)

    def _parse_detail_page(self, html_content, url):
        """解析应用详情页"""
        detail = {"detail_url": url}
        soup = BeautifulSoup(html_content, "html.parser")

        # 从 body data 属性提取基本信息
        body = soup.find("body")
        if body:
            detail["app_id"] = body.get("data-app-id", "")
            detail["app_name"] = body.get("data-title", "")
            detail["package_name"] = body.get("data-pn", "")
            detail["app_vid"] = body.get("data-app-vid", "")

        # 应用名称
        name_tag = soup.find("span", class_="title")
        if not name_tag:
            name_tag = soup.find("p", class_="app-name")
        if name_tag:
            detail["name"] = name_tag.get_text(strip=True)

        # 图标
        icon_tag = soup.find("img", class_="app-icon")
        if icon_tag:
            detail["icon"] = icon_tag.get("src", "")

        # 版本号
        version_tag = soup.find("dd", class_="perms")
        if version_tag:
            first_dt = version_tag.find_previous("dt")
            if first_dt and "版本" in first_dt.get_text():
                detail["version"] = version_tag.get_text(strip=True)

        # 开发者/开发商
        dev_tag = soup.find("span", class_="dev-sites")
        if dev_tag:
            detail["developer"] = dev_tag.get_text(strip=True)

        # 下载按钮上的信息
        install_btn = soup.find("a", class_="install-btn")
        if install_btn:
            detail["install_count"] = install_btn.get("data-install", "")
            detail["version_name"] = install_btn.get("data-app-vname", "")
            detail["version_code"] = install_btn.get("data-app-vcode", "")
            detail["category_id"] = install_btn.get("data-app-categoryid", "")

        # 应用简介
        desc_tag = soup.find("div", class_="app-info")
        if not desc_tag:
            desc_tag = soup.find("div", itemprop="description")
        if desc_tag:
            detail["description"] = desc_tag.get_text(strip=True)

        # 更新时间
        update_tag = soup.find("time")
        if update_tag:
            detail["update_time"] = update_tag.get_text(strip=True)

        return detail

    def get_download_url(self, app_id):
        """
        获取 APK 下载链接

        Args:
            app_id: 应用ID

        Returns:
            str: APK 下载链接，失败返回 None
        """
        params = {"appId": app_id}
        headers = {
            "Referer": "https://www.wandoujia.com/",
            "Accept": "application/json, text/plain, */*",
        }

        try:
            resp = self.session.get(
                self.DOWNLOAD_URL_API, params=params, headers=headers, timeout=15
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, json.JSONDecodeError) as e:
            print(f"[错误] 获取下载链接失败: {e}", file=sys.stderr)
            return None

        if data.get("state", {}).get("code") != 2000000:
            msg = data.get("state", {}).get("msg", "未知错误")
            print(f"[错误] 获取下载链接失败: {msg}", file=sys.stderr)
            return None

        return data.get("data")

    def download_apk(self, app_id, save_path=None, save_dir=".", show_progress=True):
        """
        下载 APK 文件

        Args:
            app_id: 应用ID
            save_path: 保存路径（可选，不指定则自动命名）
            save_dir: 保存目录（默认当前目录）
            show_progress: 是否显示下载进度

        Returns:
            str: 保存的文件路径，失败返回 None
        """
        # 先获取下载链接
        download_url = self.get_download_url(app_id)
        if not download_url:
            return None

        # 如果没有指定保存路径，从 URL 中提取文件名
        if not save_path:
            parsed = urlparse(download_url)
            fname = parse_qs(parsed.query).get("fname", ["app.apk"])
            filename = fname[0] if fname else f"{app_id}.apk"
            if not filename.endswith(".apk"):
                filename += ".apk"
            save_path = os.path.join(save_dir, filename)

        # 确保目录存在
        os.makedirs(os.path.dirname(os.path.abspath(save_path)) or ".", exist_ok=True)

        headers = {
            "Referer": "https://www.wandoujia.com/",
        }

        try:
            resp = self.session.get(
                download_url, headers=headers, stream=True, timeout=30
            )
            resp.raise_for_status()

            total_size = int(resp.headers.get("content-length", 0))
            downloaded = 0

            with open(save_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=8192):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if show_progress and total_size > 0:
                            percent = downloaded / total_size * 100
                            mb_downloaded = downloaded / (1024 * 1024)
                            mb_total = total_size / (1024 * 1024)
                            print(
                                f"\r[下载中] {mb_downloaded:.1f}MB / {mb_total:.1f}MB "
                                f"({percent:.1f}%)",
                                end="",
                                flush=True,
                            )

            if show_progress:
                print()  # 换行

            # 验证文件大小
            actual_size = os.path.getsize(save_path)
            if total_size > 0 and actual_size < total_size * 0.9:
                print(f"[警告] 下载可能不完整: {actual_size} / {total_size} 字节")

            return save_path

        except requests.RequestException as e:
            print(f"\n[错误] 下载失败: {e}", file=sys.stderr)
            # 清理不完整的文件
            if os.path.exists(save_path):
                os.remove(save_path)
            return None


def print_app_list(apps, page=1):
    """打印应用列表"""
    if not apps:
        print("没有找到相关应用")
        return

    print(f"\n=== 搜索结果 (第 {page} 页) ===")
    print(f"共找到 {len(apps)} 个应用\n")

    for i, app in enumerate(apps, 1):
        name = app.get("name", "未知")
        package = app.get("package_name", "")
        install = app.get("install_count", "未知")
        version = app.get("version_name", "")
        desc = app.get("description", "")

        print(f"  [{i}] {name}")
        if package:
            print(f"      包名: {package}")
        if version:
            print(f"      版本: {version}")
        if install:
            print(f"      安装: {install}")
        if desc:
            short_desc = desc[:60] + "..." if len(desc) > 60 else desc
            print(f"      简介: {short_desc}")
        print()


def interactive_search(client):
    """交互式搜索模式"""
    print("=== 豌豆荚应用搜索工具 ===")
    print("输入关键词搜索应用，输入 q 退出\n")

    while True:
        try:
            keyword = input("请输入搜索关键词: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见！")
            break

        if keyword.lower() in ("q", "quit", "exit"):
            print("再见！")
            break

        if not keyword:
            continue

        page = 1
        while True:
            apps = client.search(keyword, page=page)
            print_app_list(apps, page)

            if not apps:
                break

            print("\n操作:")
            print("  <序号>   - 查看应用详情并下载")
            print("  n        - 下一页")
            print("  p        - 上一页")
            print("  s        - 重新搜索")
            print("  q        - 退出")

            try:
                choice = input("\n请选择操作: ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                print("\n再见！")
                return

            if choice == "q":
                print("再见！")
                return
            elif choice == "s":
                break
            elif choice == "n":
                page += 1
                continue
            elif choice == "p":
                if page > 1:
                    page -= 1
                continue
            elif choice.isdigit():
                idx = int(choice) - 1
                if 0 <= idx < len(apps):
                    app = apps[idx]
                    handle_app_detail(client, app)
                else:
                    print("无效的序号")
            else:
                print("无效的输入")


def handle_app_detail(client, app):
    """处理应用详情查看和下载"""
    app_id = app.get("app_id", "")
    name = app.get("name", "未知应用")

    print(f"\n=== {name} 详情 ===")

    # 获取详细信息
    detail = client.get_app_detail(app_id or app.get("package_name", ""))
    if detail:
        for key, value in detail.items():
            if value and key not in ("detail_url", "icon_url"):
                print(f"  {key}: {value}")

    print()

    # 询问是否下载
    try:
        dl_choice = input("是否下载 APK? (y/n): ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        return

    if dl_choice == "y":
        if not app_id:
            print("[错误] 缺少应用ID，无法下载")
            return

        print(f"正在获取下载链接...")
        download_url = client.get_download_url(app_id)
        if download_url:
            print(f"下载链接: {download_url[:100]}...")
            print("开始下载...")

            save_path = client.download_apk(app_id)
            if save_path:
                print(f"\n[成功] APK 已保存到: {save_path}")
            else:
                print("\n[失败] 下载失败")
        else:
            print("[失败] 无法获取下载链接")


def main():
    parser = argparse.ArgumentParser(
        description="豌豆荚应用搜索和下载工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 交互式模式
  python wandoujia_search_download.py

  # 搜索应用
  python wandoujia_search_download.py search 微信

  # 搜索并显示详情
  python wandoujia_search_download.py search 微信 --detail

  # 直接下载（通过应用ID）
  python wandoujia_search_download.py download 596157

  # 下载到指定目录
  python wandoujia_search_download.py download 596157 -o ./apks/
        """,
    )

    subparsers = parser.add_subparsers(dest="command", help="可用命令")

    # 搜索命令
    search_parser = subparsers.add_parser("search", help="搜索应用")
    search_parser.add_argument("keyword", help="搜索关键词")
    search_parser.add_argument("-p", "--page", type=int, default=1, help="页码")
    search_parser.add_argument(
        "-n", "--num", type=int, default=10, help="显示结果数量"
    )
    search_parser.add_argument(
        "--detail", action="store_true", help="显示每个应用的详情"
    )
    search_parser.add_argument(
        "--download", action="store_true", help="下载第一个匹配的应用"
    )

    # 下载命令
    download_parser = subparsers.add_parser("download", help="下载 APK")
    download_parser.add_argument("app_id", help="应用ID")
    download_parser.add_argument(
        "-o", "--output", default=".", help="保存目录或文件路径"
    )

    # 详情命令
    detail_parser = subparsers.add_parser("detail", help="查看应用详情")
    detail_parser.add_argument("app_id", help="应用ID或包名")

    args = parser.parse_args()

    client = WandoujiaClient()

    if args.command is None:
        # 默认进入交互式模式
        interactive_search(client)

    elif args.command == "search":
        apps = client.search(args.keyword, page=args.page)
        apps = apps[: args.num]
        print_app_list(apps, args.page)

        if args.detail and apps:
            for app in apps:
                app_id = app.get("app_id", "")
                if app_id:
                    detail = client.get_app_detail(app_id)
                    if detail:
                        print(f"\n--- {app.get('name', '未知')} 详情 ---")
                        for k, v in detail.items():
                            if v and k not in ("detail_url", "icon_url"):
                                print(f"  {k}: {v}")

        if args.download and apps:
            app = apps[0]
            app_id = app.get("app_id", "")
            if app_id:
                print(f"\n正在下载: {app.get('name', '未知')}")
                save_path = client.download_apk(app_id)
                if save_path:
                    print(f"\n[成功] 已保存到: {save_path}")

    elif args.command == "download":
        print(f"正在下载应用 {args.app_id}...")
        save_path = client.download_apk(args.app_id, save_dir=args.output)
        if save_path:
            print(f"\n[成功] 已保存到: {save_path}")
        else:
            print("\n[失败] 下载失败")
            sys.exit(1)

    elif args.command == "detail":
        detail = client.get_app_detail(args.app_id)
        if detail:
            print("=== 应用详情 ===")
            for k, v in detail.items():
                if v:
                    print(f"  {k}: {v}")
        else:
            print("未找到应用详情")
            sys.exit(1)


if __name__ == "__main__":
    main()
