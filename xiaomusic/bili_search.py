#!/usr/bin/env python3
"""
Bilibili 视频/音频搜索与直达模块
支持：
1. BV号或视频链接精准直达解析 (毫秒级)
2. 短链接 b23.tv 自动重定向还原并提取 BV 号
3. 关键词全网 Wbi 鉴权搜索
"""

import asyncio
import hashlib
import logging
import re
import time
import urllib.parse
from typing import Any, Dict, List, Optional

import aiohttp

log = logging.getLogger(__name__)

MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
    61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
    36, 20, 34, 44, 52,
]

_wbi_cache = {
    "img_key": "",
    "sub_key": "",
    "expire_at": 0,
}

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0 Safari/537.36"
    ),
    "Referer": "https://www.bilibili.com",
}


def _get_mixin_key(orig: str) -> str:
    return "".join([orig[i] for i in MIXIN_KEY_ENC_TAB])[:32]


def _enc_wbi(params: dict, img_key: str, sub_key: str) -> dict:
    mixin_key = _get_mixin_key(img_key + sub_key)
    curr_time = round(time.time())
    params["wts"] = curr_time
    params = dict(sorted(params.items()))
    params = {
        k: "".join(filter(lambda chr: chr not in "!'()*", str(v)))
        for k, v in params.items()
    }
    query = urllib.parse.urlencode(params)
    wbi_sign = hashlib.md5((query + mixin_key).encode()).hexdigest()
    params["w_rid"] = wbi_sign
    return params


def _clean_title(text: str) -> str:
    """去除 HTML 标签"""
    if not text:
        return ""
    clean = re.sub(r"<[^>]+>", "", text)
    return clean.strip()


async def get_wbi_keys() -> tuple[str, str]:
    now = time.time()
    if _wbi_cache["expire_at"] > now and _wbi_cache["img_key"]:
        return _wbi_cache["img_key"], _wbi_cache["sub_key"]

    async with aiohttp.ClientSession(headers=HEADERS) as session:
        async with session.get("https://api.bilibili.com/x/web-interface/nav") as resp:
            data = await resp.json()
            wbi_img = data.get("data", {}).get("wbi_img", {})
            img_url = wbi_img.get("img_url", "")
            sub_url = wbi_img.get("sub_url", "")
            img_key = img_url.split("/")[-1].split(".")[0]
            sub_key = sub_url.split("/")[-1].split(".")[0]
            _wbi_cache["img_key"] = img_key
            _wbi_cache["sub_key"] = sub_key
            _wbi_cache["expire_at"] = now + 12 * 3600
            return img_key, sub_key


async def resolve_bvid_from_input(text: str) -> Optional[str]:
    """从输入的文本中提取 BV 号，支持纯 BV 号、完整 URL、b23.tv 短链接"""
    clean_text = text.strip()

    # 1. 直接正则匹配标准 BV 号 (如 BV14K4y1A7vD)
    bv_match = re.search(r"(BV[a-zA-Z0-9]{10})", clean_text, re.IGNORECASE)
    if bv_match:
        return bv_match.group(1)

    # 2. 短链接 b23.tv 处理
    if "b23.tv" in clean_text:
        try:
            # 提取 url
            url_match = re.search(r"https?://b23\.tv/[a-zA-Z0-9]+", clean_text)
            target_url = url_match.group(0) if url_match else clean_text
            async with aiohttp.ClientSession(headers=HEADERS) as session:
                async with session.get(target_url, allow_redirects=True) as resp:
                    final_url = str(resp.url)
                    match = re.search(r"(BV[a-zA-Z0-9]{10})", final_url, re.IGNORECASE)
                    if match:
                        return match.group(1)
        except Exception as e:
            log.warning(f"resolve b23.tv short link failed: {e}")

    return None


async def get_video_detail_by_bvid(bvid: str) -> Optional[Dict[str, Any]]:
    """精准获取单个 BV 视频详情 (毫秒级直达接口)"""
    url = f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}"
    try:
        async with aiohttp.ClientSession(headers=HEADERS) as session:
            async with session.get(url) as resp:
                res = await resp.json()
                if res.get("code") == 0:
                    d = res.get("data", {})
                    pic = d.get("pic", "")
                    if pic.startswith("//"):
                        pic = "https:" + pic
                    elif pic.startswith("http://"):
                        pic = "https://" + pic[7:]

                    duration_sec = d.get("duration", 0)
                    mins = duration_sec // 60
                    secs = duration_sec % 60
                    duration_str = f"{mins}:{secs:02d}"

                    return {
                        "bvid": d.get("bvid", bvid),
                        "title": _clean_title(d.get("title", "")),
                        "author": d.get("owner", {}).get("name", "UP主"),
                        "duration": duration_str,
                        "play": d.get("stat", {}).get("view", 0),
                        "pic": pic,
                        "url": f"https://www.bilibili.com/video/{d.get('bvid', bvid)}",
                        "is_direct": True,
                    }
    except Exception as e:
        log.warning(f"get_video_detail_by_bvid failed: {e}")
    return None


async def search_bilibili(keyword: str, page: int = 1, page_size: int = 20) -> Dict[str, Any]:
    """搜索 Bilibili 视频，优先支持 BV 号或视频链接直接解析直达"""
    clean_keyword = keyword.strip()
    if not clean_keyword:
        return {"success": False, "error": "请输入搜索内容", "data": []}

    # 1. 优先尝试 BV 号 / 视频链接直达解析
    bvid = await resolve_bvid_from_input(clean_keyword)
    if bvid:
        detail = await get_video_detail_by_bvid(bvid)
        if detail:
            log.info(f"Direct match Bilibili video: {bvid} -> {detail['title']}")
            return {
                "success": True,
                "total": 1,
                "is_direct": True,
                "data": [detail],
            }

    # 2. 关键词全网检索
    try:
        img_key, sub_key = await get_wbi_keys()
        params = {
            "keyword": clean_keyword,
            "search_type": "video",
            "page": page,
            "page_size": page_size,
        }
        signed_params = _enc_wbi(params, img_key, sub_key)
        url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" + urllib.parse.urlencode(signed_params)

        async with aiohttp.ClientSession(headers=HEADERS) as session:
            async with session.get(url) as resp:
                res = await resp.json()
                if res.get("code") != 0:
                    return {"success": False, "error": res.get("message", "B站接口请求失败"), "data": []}

                raw_items = res.get("data", {}).get("result", [])
                results = []
                for item in raw_items:
                    item_bvid = item.get("bvid", "")
                    if not item_bvid:
                        continue
                    pic = item.get("pic", "")
                    if pic.startswith("//"):
                        pic = "https:" + pic
                    elif pic.startswith("http://"):
                        pic = "https://" + pic[7:]

                    results.append({
                        "bvid": item_bvid,
                        "title": _clean_title(item.get("title", "")),
                        "author": item.get("author", ""),
                        "duration": item.get("duration", ""),
                        "play": item.get("play", 0),
                        "pic": pic,
                        "url": f"https://www.bilibili.com/video/{item_bvid}",
                        "is_direct": False,
                    })

                return {
                    "success": True,
                    "total": res.get("data", {}).get("numResults", len(results)),
                    "is_direct": False,
                    "data": results,
                }
    except Exception as e:
        log.exception(f"search_bilibili error: {e}")
        return {"success": False, "error": str(e), "data": []}
