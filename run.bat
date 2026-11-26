@echo off
cd /d %~dp0
python -m pip install -q flask oracledb
python app.py
pause
