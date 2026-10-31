@echo off
chcp 65001 >nul
title XiaoMusic

if exist "C:\app\ffmpeg\bin" (
    set "PATH=C:\app\ffmpeg\bin;%PATH%"
)

echo ====================================================
echo 正在启动 XiaoMusic 本地服务...
echo 访问地址: http://localhost:58090
echo ====================================================

if exist ".\.venv\Scripts\python.exe" (
    .\.venv\Scripts\python.exe -m xiaomusic.cli --port 58090
) else (
    echo [错误] 未找到 .venv 虚拟环境，请先在当前目录下创建:
    echo   uv venv --python 3.13
    echo   uv pip install -e .
)
pause
