@echo off
cd /d %~dp0
if not exist lib mkdir lib
if not exist lib\ojdbc11.jar curl -L -o lib\ojdbc11.jar https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.5.0.24.07/ojdbc11-23.5.0.24.07.jar
if not exist out mkdir out
javac -d out src\Main.java
if errorlevel 1 pause & exit /b
java -cp "out;lib\ojdbc11.jar" Main
pause
